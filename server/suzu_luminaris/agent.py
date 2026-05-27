"""The Devin-style agent loop.

The loop runs as a server-side background task per session. Every event
(delta, tool_call, tool_result, usage, status, done, error) is persisted
to `agent_events` with a monotonic `seq`, then fanned out via the per-
session asyncio Event so long-poll subscribers wake up.

This means:

  * Closing the client APK does NOT cancel the agent — the loop keeps
    running and persisting events.
  * Re-opening the APK resumes via `GET /v1/sessions/{id}/events?since=N`
    which replays all events with seq > N — zero loss.
  * Multiple panels/clients can observe the same session concurrently.

Token usage is captured from the provider stream and accumulated per
session (`tokens_in`, `tokens_out` on the `sessions` row) plus per-turn
`usage` events for the UI's live counter.
"""

from __future__ import annotations

import asyncio
import json
from typing import Any

from . import providers, tools
from .db import Db
from .logbus import LogBus
from .schemas import AdminConfig, ChatMessage, TokenUsage, ToolCall, ToolResult

# Tight system prompt — every token here is paid on every turn.
SYSTEM_PROMPT = (
    "You are SuzuAI Luminaris — a Devin-style coding agent paired with an "
    "Android app. Operate on the user's remote SSH host via tools; never "
    "guess. Use `read_file` before `write_file`. For APK work: "
    "`apk_decompile` → edit → `apk_recompile` → `apk_sign`. Signed APKs "
    "auto-register in the panel. Be concise; favour fewer tokens."
)

# History compaction limits — keep the prompt cheap.
MAX_TOOL_OUTPUT_IN_HISTORY = 1600  # chars; full output still saved in DB
HISTORY_HEAD_KEEP = 2  # always keep first N non-system turns
HISTORY_TAIL_KEEP = 30  # keep most recent N items
HISTORY_TOTAL_LIMIT = 60  # rough cap; trim to head+tail when above


class _Bus:
    """Per-session async fan-out for long-poll waiters.

    One asyncio.Event per session; `notify` sets+clears so any pending
    waiter wakes and re-polls SQLite for events with seq > since.
    """

    def __init__(self) -> None:
        self._events: dict[str, asyncio.Event] = {}

    def get(self, sid: str) -> asyncio.Event:
        ev = self._events.get(sid)
        if ev is None:
            ev = asyncio.Event()
            self._events[sid] = ev
        return ev

    def notify(self, sid: str) -> None:
        ev = self.get(sid)
        ev.set()
        # Reset for the next wave of waiters.
        ev.clear()


# Global per-process registry; the FastAPI app shares one instance.
bus = _Bus()

# session_id -> currently running task. Used to reject concurrent /v1/chat.
_running: dict[str, asyncio.Task[None]] = {}


def is_running(sid: str) -> bool:
    t = _running.get(sid)
    return t is not None and not t.done()


async def start(
    db: Db,
    cfg: AdminConfig,
    logbus: LogBus,
    session_id: str,
    user_message: str,
    max_iterations: int | None = None,
    attachment_ids: list[str] | None = None,
) -> None:
    """Kick off the agent loop in a background task. Returns immediately.

    Raises RuntimeError if a task is already running for this session.
    """
    if is_running(session_id):
        raise RuntimeError("session already running")
    task = asyncio.create_task(
        _run(db, cfg, logbus, session_id, user_message, max_iterations, attachment_ids or [])
    )
    _running[session_id] = task


async def _run(
    db: Db,
    cfg: AdminConfig,
    logbus: LogBus,
    session_id: str,
    user_message: str,
    max_iterations: int | None,
    attachment_ids: list[str],
) -> None:
    cap = max_iterations or cfg.max_iterations
    try:
        await db.set_session_status(session_id, "typing")
        await _emit(db, session_id, "status", status="typing")

        # If the user attached files, append a manifest to the message so the
        # assistant knows which attachments are available (and their ids).
        manifest = ""
        if attachment_ids:
            lines: list[str] = []
            for aid in attachment_ids:
                att = await db.get_attachment(aid)
                if att is None or att.session_id != session_id:
                    continue
                lines.append(f"- id={att.id}  name={att.name}  mime={att.mime}  size={att.size}B")
            if lines:
                manifest = (
                    "\n\n[attachments]\n"
                    + "\n".join(lines)
                    + "\nUse the `read_attachment` tool with the id to read them."
                )
        full_user = user_message + manifest

        # Persist the user turn first so resume-reload sees it.
        await db.append_message(session_id, ChatMessage(role="user", content=full_user))

        history: list[dict[str, Any]] = [{"role": "system", "content": SYSTEM_PROMPT}]
        prior = await db.list_messages(session_id)
        for m in prior:
            history.append(_to_provider_msg(m))

        schema = tools.schema_for_provider()
        ctx = tools.ToolContext(ssh=cfg.ssh, session_id=session_id, logbus=logbus)

        for step in range(cap):
            history = _compact_history(history)
            assistant_text = ""
            pending: list[dict[str, Any]] = []
            try:
                async for chunk in providers.stream(cfg.ai_provider, history, schema):
                    t = chunk.get("type")
                    if t == "delta":
                        text = chunk.get("text", "")
                        assistant_text += text
                        await _emit(db, session_id, "delta", delta=text)
                    elif t == "tool_call":
                        pending.append(chunk)
                        await _emit(
                            db,
                            session_id,
                            "tool_call",
                            tool_call=ToolCall(
                                id=chunk.get("id", ""),
                                name=chunk.get("name", ""),
                                args=chunk.get("args", ""),
                            ),
                        )
                    elif t == "usage":
                        prompt = int(chunk.get("prompt", 0))
                        completion = int(chunk.get("completion", 0))
                        total = int(chunk.get("total", 0)) or (prompt + completion)
                        await db.add_session_tokens(session_id, prompt, completion)
                        await _emit(
                            db,
                            session_id,
                            "usage",
                            usage=TokenUsage(
                                prompt=prompt, completion=completion, total=total
                            ),
                        )
                    elif t == "stop":
                        break
            except providers.ProviderError as e:
                await logbus.emit("ERROR", "agent.provider", str(e))
                await _emit(db, session_id, "error", message=str(e))
                await db.set_session_status(session_id, "error")
                await _emit(db, session_id, "status", status="error")
                return

            if assistant_text:
                await db.append_message(
                    session_id, ChatMessage(role="assistant", content=assistant_text)
                )
                history.append({"role": "assistant", "content": assistant_text})

            if not pending:
                await db.touch_session(session_id)
                await db.set_session_status(session_id, "idle")
                await _emit(db, session_id, "status", status="idle")
                await _emit(db, session_id, "done")
                return

            # Execute pending tools sequentially.
            await db.set_session_status(session_id, "tool")
            await _emit(db, session_id, "status", status="tool")

            tool_msgs: list[dict[str, Any]] = []
            for call in pending:
                spec = tools.by_name(call.get("name", ""))
                cid = call.get("id", f"call_{step}")
                if spec is None:
                    output = f"error: unknown tool {call.get('name')!r}"
                    is_err = True
                else:
                    args = tools.parse_args(call.get("args", "") or "{}")
                    try:
                        output = await spec.run(ctx, args)
                        is_err = output.startswith("error:") or output.startswith("ssh-error:")
                    except Exception as e:  # noqa: BLE001 — tool boundary
                        output = f"tool-error: {e!r}"
                        is_err = True

                await logbus.emit(
                    "ERROR" if is_err else "INFO",
                    f"tool.{call.get('name', '?')}",
                    output[:300],
                )

                tc = ToolCall(id=cid, name=call.get("name", ""), args=call.get("args", ""))
                tr = ToolResult(id=cid, output=output, is_error=is_err)

                await db.append_message(
                    session_id, ChatMessage(role="tool", content="", tool_call=tc)
                )
                await db.append_message(
                    session_id, ChatMessage(role="tool", content="", tool_result=tr)
                )
                await _emit(db, session_id, "tool_result", tool_result=tr)

                truncated = _truncate(output, MAX_TOOL_OUTPUT_IN_HISTORY)
                tool_msgs.append(
                    {
                        "role": "assistant",
                        "content": "",
                        "tool_calls": [
                            {
                                "id": cid,
                                "type": "function",
                                "function": {
                                    "name": call.get("name", ""),
                                    "arguments": call.get("args", "") or "{}",
                                },
                            }
                        ],
                    }
                )
                tool_msgs.append(
                    {"role": "tool", "tool_call_id": cid, "content": truncated}
                )

            history.extend(tool_msgs)
            await db.set_session_status(session_id, "typing")
            await _emit(db, session_id, "status", status="typing")

        await logbus.emit("WARN", "agent.loop", f"hit max_iterations={cap}")
        await _emit(db, session_id, "error", message=f"hit max_iterations={cap}")
        await db.set_session_status(session_id, "error")
        await _emit(db, session_id, "status", status="error")
    except Exception as e:  # noqa: BLE001 — top-level guard for background task
        await logbus.emit("ERROR", "agent.crash", repr(e))
        try:
            await _emit(db, session_id, "error", message=f"crash: {e!r}")
            await db.set_session_status(session_id, "error")
            await _emit(db, session_id, "status", status="error")
        except Exception:  # noqa: BLE001
            pass
    finally:
        _running.pop(session_id, None)


async def _emit(db: Db, sid: str, type_: str, **kwargs: Any) -> None:
    await db.append_event(sid, type_, **kwargs)
    bus.notify(sid)


def _truncate(s: str, limit: int) -> str:
    if len(s) <= limit:
        return s
    head = s[: limit // 2]
    tail = s[-limit // 2 :]
    return f"{head}\n\n…[{len(s) - limit} chars elided]…\n\n{tail}"


def _compact_history(history: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Sliding-window compaction. Keeps system + head + tail."""
    if len(history) <= HISTORY_TOTAL_LIMIT:
        return history
    system = [m for m in history if m.get("role") == "system"]
    rest = [m for m in history if m.get("role") != "system"]
    if len(rest) <= HISTORY_HEAD_KEEP + HISTORY_TAIL_KEEP:
        return history
    head = rest[:HISTORY_HEAD_KEEP]
    tail = rest[-HISTORY_TAIL_KEEP:]
    elided = len(rest) - HISTORY_HEAD_KEEP - HISTORY_TAIL_KEEP
    summary = {
        "role": "system",
        "content": f"[history compacted: {elided} earlier turns elided to save tokens]",
    }
    return system + head + [summary] + tail


def _to_provider_msg(m: ChatMessage) -> dict[str, Any]:
    """Best-effort conversion of stored ChatMessage → provider wire format."""
    if m.role in ("user", "assistant", "system"):
        return {"role": m.role, "content": m.content}
    if m.tool_call is not None:
        return {
            "role": "assistant",
            "content": "",
            "tool_calls": [
                {
                    "id": m.tool_call.id,
                    "type": "function",
                    "function": {"name": m.tool_call.name, "arguments": m.tool_call.args},
                }
            ],
        }
    if m.tool_result is not None:
        return {
            "role": "tool",
            "tool_call_id": m.tool_result.id,
            "content": _truncate(m.tool_result.output, MAX_TOOL_OUTPUT_IN_HISTORY),
        }
    return {"role": m.role, "content": m.content}


# Legacy compatibility — keep the old streaming generator name importable in case
# other code still references it. Returns immediately if the loop is already
# running; otherwise spins one up and yields nothing (transport is replaced by
# the events endpoint).
async def chat_stream(*_a: Any, **_kw: Any) -> Any:  # pragma: no cover
    raise NotImplementedError("Use agent.start() + /v1/sessions/{id}/events instead.")


# Backwards-compat StreamWire frame (still used by legacy tests, if any).
class StreamWire:
    @staticmethod
    def frame(type_: str, **payload: Any) -> bytes:
        ev = {"type": type_, **payload}
        return f"data: {json.dumps(ev)}\n\n".encode("utf-8")
