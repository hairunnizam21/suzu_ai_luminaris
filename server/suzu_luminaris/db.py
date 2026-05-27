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

from .schemas import AdminConfig, ChatMessage, Session, ToolCall, ToolResult

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
            """
        )
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
            "SELECT id, title, created_at, updated_at FROM sessions ORDER BY updated_at DESC"
        ) as cur:
            rows = await cur.fetchall()
        return [Session(id=r[0], title=r[1], created_at=r[2], updated_at=r[3]) for r in rows]

    async def create_session(self, title: str) -> Session:
        sid = uuid.uuid4().hex[:12]
        now = time.time()
        await self.conn.execute(
            "INSERT INTO sessions(id, title, created_at, updated_at) VALUES (?, ?, ?, ?)",
            (sid, title, now, now),
        )
        await self.conn.commit()
        return Session(id=sid, title=title, created_at=now, updated_at=now)

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
