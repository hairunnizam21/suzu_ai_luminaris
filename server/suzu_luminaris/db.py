"""SQLite storage. Single connection, async wrapper.

Three tables:
  * config     — singleton (id = 0) holding AdminConfig as JSON.
  * sessions   — id, title, created_at, updated_at.
  * messages   — id, session_id, role, content, tool_json.

We deliberately use the simplest possible schema. The agent treats messages
as an append-only log keyed by session_id.
"""

from __future__ import annotations

import json
import os
import time
import uuid
from pathlib import Path
from typing import Any

import aiosqlite

from .schemas import (
    AdminConfig,
    AgentEvent,
    ChatMessage,
    Session,
    TokenUsage,
    ToolCall,
    ToolResult,
)

DEFAULT_PATH = Path(os.environ.get("SUZU_DB", "./sessions.db")).expanduser().resolve()


class Db:
    def __init__(self, path: Path = DEFAULT_PATH):
        self.path = path
        self._conn: aiosqlite.Connection | None = None

    async def connect(self) -> None:
        self._conn = await aiosqlite.connect(self.path)
        await self._conn.execute("PRAGMA journal_mode=WAL;")
        await self._conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS config (
                id INTEGER PRIMARY KEY,
                payload TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS sessions (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                created_at REAL NOT NULL,
                updated_at REAL NOT NULL
            );
            CREATE TABLE IF NOT EXISTS messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                tool_json TEXT,
                ts REAL NOT NULL,
                FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE
            );
            CREATE INDEX IF NOT EXISTS idx_messages_session ON messages(session_id);

            CREATE TABLE IF NOT EXISTS agent_events (
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL,
                ts REAL NOT NULL,
                type TEXT NOT NULL,
                payload TEXT NOT NULL,
                FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE
            );
            CREATE INDEX IF NOT EXISTS idx_events_session_seq
                ON agent_events(session_id, seq);
            """
        )
        # Best-effort column additions for older DBs (no-op if already present).
        for stmt in (
            "ALTER TABLE sessions ADD COLUMN status TEXT NOT NULL DEFAULT 'idle'",
            "ALTER TABLE sessions ADD COLUMN tokens_in INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE sessions ADD COLUMN tokens_out INTEGER NOT NULL DEFAULT 0",
        ):
            try:
                await self._conn.execute(stmt)
            except aiosqlite.OperationalError:
                pass
        await self._conn.commit()

    async def close(self) -> None:
        if self._conn is not None:
            await self._conn.close()
            self._conn = None

    @property
    def conn(self) -> aiosqlite.Connection:
        if self._conn is None:
            raise RuntimeError("db not connected")
        return self._conn

    # ----- config ------------------------------------------------------

    async def get_config(self) -> AdminConfig:
        async with self.conn.execute("SELECT payload FROM config WHERE id = 0") as cur:
            row = await cur.fetchone()
        if row is None:
            return AdminConfig()
        return AdminConfig.model_validate_json(row[0])

    async def put_config(self, cfg: AdminConfig) -> AdminConfig:
        await self.conn.execute(
            "INSERT INTO config(id, payload) VALUES(0, ?) "
            "ON CONFLICT(id) DO UPDATE SET payload=excluded.payload",
            (cfg.model_dump_json(),),
        )
        await self.conn.commit()
        return cfg

    async def wipe_config(self) -> AdminConfig:
        await self.conn.execute("DELETE FROM config")
        await self.conn.commit()
        return AdminConfig()

    # ----- sessions ----------------------------------------------------

    async def list_sessions(self) -> list[Session]:
        async with self.conn.execute(
            "SELECT s.id, s.title, s.created_at, s.updated_at, s.status,"
            "       s.tokens_in, s.tokens_out,"
            "       COALESCE((SELECT MAX(seq) FROM agent_events WHERE session_id = s.id), 0)"
            " FROM sessions s ORDER BY s.updated_at DESC"
        ) as cur:
            rows = await cur.fetchall()
        return [
            Session(
                id=r[0],
                title=r[1],
                created_at=r[2],
                updated_at=r[3],
                status=r[4],
                tokens_in=r[5],
                tokens_out=r[6],
                last_event_seq=r[7],
            )
            for r in rows
        ]

    async def get_session(self, sid: str) -> Session | None:
        async with self.conn.execute(
            "SELECT id, title, created_at, updated_at, status, tokens_in, tokens_out,"
            " COALESCE((SELECT MAX(seq) FROM agent_events WHERE session_id = ?), 0)"
            " FROM sessions WHERE id = ?",
            (sid, sid),
        ) as cur:
            r = await cur.fetchone()
        if r is None:
            return None
        return Session(
            id=r[0],
            title=r[1],
            created_at=r[2],
            updated_at=r[3],
            status=r[4],
            tokens_in=r[5],
            tokens_out=r[6],
            last_event_seq=r[7],
        )

    async def create_session(self, title: str) -> Session:
        sid = uuid.uuid4().hex[:12]
        now = time.time()
        await self.conn.execute(
            "INSERT INTO sessions(id, title, created_at, updated_at, status, tokens_in, tokens_out)"
            " VALUES (?, ?, ?, ?, 'idle', 0, 0)",
            (sid, title, now, now),
        )
        await self.conn.commit()
        return Session(id=sid, title=title, created_at=now, updated_at=now)

    async def set_session_status(self, sid: str, status: str) -> None:
        await self.conn.execute(
            "UPDATE sessions SET status = ?, updated_at = ? WHERE id = ?",
            (status, time.time(), sid),
        )
        await self.conn.commit()

    async def add_session_tokens(self, sid: str, prompt: int, completion: int) -> None:
        await self.conn.execute(
            "UPDATE sessions SET tokens_in = tokens_in + ?, tokens_out = tokens_out + ?,"
            " updated_at = ? WHERE id = ?",
            (prompt, completion, time.time(), sid),
        )
        await self.conn.commit()

    async def delete_session(self, sid: str) -> None:
        await self.conn.execute("DELETE FROM messages WHERE session_id = ?", (sid,))
        await self.conn.execute("DELETE FROM sessions WHERE id = ?", (sid,))
        await self.conn.commit()

    async def touch_session(self, sid: str) -> None:
        await self.conn.execute(
            "UPDATE sessions SET updated_at = ? WHERE id = ?", (time.time(), sid)
        )
        await self.conn.commit()

    # ----- messages ----------------------------------------------------

    async def list_messages(self, sid: str) -> list[ChatMessage]:
        async with self.conn.execute(
            "SELECT role, content, tool_json FROM messages "
            "WHERE session_id = ? ORDER BY id ASC",
            (sid,),
        ) as cur:
            rows = await cur.fetchall()
        out: list[ChatMessage] = []
        for role, content, tool_json in rows:
            tool_call: ToolCall | None = None
            tool_result: ToolResult | None = None
            if tool_json:
                payload: dict[str, Any] = json.loads(tool_json)
                if payload.get("kind") == "call":
                    tool_call = ToolCall(**payload["data"])
                elif payload.get("kind") == "result":
                    tool_result = ToolResult(**payload["data"])
            out.append(
                ChatMessage(role=role, content=content, tool_call=tool_call, tool_result=tool_result)
            )
        return out

    async def append_message(self, sid: str, msg: ChatMessage) -> None:
        tool_json: str | None = None
        if msg.tool_call is not None:
            tool_json = json.dumps({"kind": "call", "data": msg.tool_call.model_dump()})
        elif msg.tool_result is not None:
            tool_json = json.dumps({"kind": "result", "data": msg.tool_result.model_dump()})
        await self.conn.execute(
            "INSERT INTO messages(session_id, role, content, tool_json, ts) VALUES (?, ?, ?, ?, ?)",
            (sid, msg.role, msg.content, tool_json, time.time()),
        )
        await self.conn.commit()

    # ----- agent events ----------------------------------------------

    async def append_event(
        self,
        sid: str,
        type_: str,
        *,
        delta: str | None = None,
        tool_call: ToolCall | None = None,
        tool_result: ToolResult | None = None,
        usage: TokenUsage | None = None,
        status: str | None = None,
        message: str | None = None,
    ) -> AgentEvent:
        payload: dict[str, Any] = {}
        if delta is not None:
            payload["delta"] = delta
        if tool_call is not None:
            payload["tool_call"] = tool_call.model_dump()
        if tool_result is not None:
            payload["tool_result"] = tool_result.model_dump()
        if usage is not None:
            payload["usage"] = usage.model_dump()
        if status is not None:
            payload["status"] = status
        if message is not None:
            payload["message"] = message
        ts = time.time()
        cur = await self.conn.execute(
            "INSERT INTO agent_events(session_id, ts, type, payload) VALUES (?, ?, ?, ?)",
            (sid, ts, type_, json.dumps(payload)),
        )
        await self.conn.commit()
        seq = cur.lastrowid or 0
        return AgentEvent(
            seq=seq,
            ts=ts,
            type=type_,
            delta=delta,
            tool_call=tool_call,
            tool_result=tool_result,
            usage=usage,
            status=status,
            message=message,
        )

    async def list_events(self, sid: str, since: int, limit: int = 500) -> list[AgentEvent]:
        async with self.conn.execute(
            "SELECT seq, ts, type, payload FROM agent_events"
            " WHERE session_id = ? AND seq > ? ORDER BY seq ASC LIMIT ?",
            (sid, since, limit),
        ) as cur:
            rows = await cur.fetchall()
        out: list[AgentEvent] = []
        for seq, ts, type_, payload_json in rows:
            p: dict[str, Any] = json.loads(payload_json) if payload_json else {}
            out.append(
                AgentEvent(
                    seq=seq,
                    ts=ts,
                    type=type_,
                    delta=p.get("delta"),
                    tool_call=ToolCall(**p["tool_call"]) if p.get("tool_call") else None,
                    tool_result=ToolResult(**p["tool_result"]) if p.get("tool_result") else None,
                    usage=TokenUsage(**p["usage"]) if p.get("usage") else None,
                    status=p.get("status"),
                    message=p.get("message"),
                )
            )
        return out

    async def max_event_seq(self, sid: str) -> int:
        async with self.conn.execute(
            "SELECT COALESCE(MAX(seq), 0) FROM agent_events WHERE session_id = ?",
            (sid,),
        ) as cur:
            r = await cur.fetchone()
        return int(r[0]) if r else 0
