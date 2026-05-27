"""The Devin-style agent loop.

Each turn:
  1. Send full message history + tool schema to the provider.
  2. Stream `delta` events back as wire SSE.
  3. If the model emitted tool calls, run them sequentially via the SSH host,
     append both call + result to history, and loop.
  4. If the model stopped without calls, emit `done` and exit.

The loop is capped by `max_iterations` (default 100) so a confused model can't
spin forever.
"""

from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator
from typing import Any

from . import providers, tools
from .db import Db
from .logbus import LogBus
from .schemas import AdminConfig, ChatMessage, StreamEvent, ToolCall, ToolResult

SYSTEM_PROMPT = """You are SuzuAI Luminaris — a Devin-style coding agent paired with an Android app.

You operate on a remote SSH host owned by the user. Always prefer running
real commands via the `shell` tool rather than guessing. When editing files,
use `read_file` to inspect, then `write_file` to overwrite the entire file.

For APK work: `apk_decompile` → edit files → `apk_recompile` → `apk_sign`.
Signed artifacts are automatically registered in the panel's APK list so the
user can download them.

Be concise. Prefer fewer tokens. Never expose secrets in plain text."""


class StreamWire:
    """Helper to encode `dict[str, Any]` events as `data: <json>\n\n` frames."""

    @staticmethod
    def frame(type_: str, **payload: Any) -> bytes:
        ev = {"type": type_, **payload}
        return f"data: {json.dumps(ev)}\n\n".encode("utf-8")


async def chat_stream(
    db: Db,
    cfg: AdminConfig,
    logbus: LogBus,
    session_id: str,
    user_message: str,
    max_iterations: int | None = None,
) -> AsyncIterator[bytes]:
    """Server-side generator. Yields raw SSE frames."""
    cap = max_iterations or cfg.max_iterations
    history: list[dict[str, Any]] = [{"role": "system", "content": SYSTEM_PROMPT}]
    prior = await db.list_messages(session_id)
    for m in prior:
        history.append(_to_provider_msg(m))

    user_msg = ChatMessage(role="user", content=user_message)
    await db.append_message(session_id, user_msg)
    history.append({"role": "user", "content": user_message})

    schema = tools.schema_for_provider()
    ctx = tools.ToolContext(ssh=cfg.ssh, session_id=session_id, logbus=logbus)

    for step in range(cap):
        assistant_text = ""
        pending: list[dict[str, Any]] = []

        try:
            async for chunk in providers.stream(cfg.ai_provider, history, schema):
                t = chunk.get("type")
                if t == "delta":
                    text = chunk.get("text", "")
                    assistant_text += text
                    yield StreamWire.frame("delta", delta=text)
                elif t == "tool_call":
                    pending.append(chunk)
                    yield StreamWire.frame(
                        "tool_call",
                        tool_call={
                            "id": chunk.get("id", ""),
                            "name": chunk.get("name", ""),
                            "args": chunk.get("args", ""),
                        },
                    )
                elif t == "stop":
                    break
        except providers.ProviderError as e:
            await logbus.emit("ERROR", "agent.provider", str(e))
            yield StreamWire.frame("error", message=str(e))
            return

        if assistant_text:
            await db.append_message(
                session_id, ChatMessage(role="assistant", content=assistant_text)
            )
            history.append({"role": "assistant", "content": assistant_text})

        if not pending:
            await db.touch_session(session_id)
            yield StreamWire.frame("done")
            return

        # Execute every pending tool, push results.
        tool_msgs_for_history: list[dict[str, Any]] = []
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
                except Exception as e:  # noqa: BLE001 — boundary
                    output = f"tool-error: {e!r}"
                    is_err = True

            await logbus.emit(
                "ERROR" if is_err else "INFO",
                f"tool.{call.get('name', '?')}",
                output[:300],
            )

            tool_call_obj = ToolCall(id=cid, name=call.get("name", ""), args=call.get("args", ""))
            tool_result_obj = ToolResult(id=cid, output=output, is_error=is_err)

            await db.append_message(
                session_id,
                ChatMessage(role="tool", content="", tool_call=tool_call_obj),
            )
            await db.append_message(
                session_id,
                ChatMessage(role="tool", content="", tool_result=tool_result_obj),
            )

            yield StreamWire.frame("tool_result", tool_result=tool_result_obj.model_dump())

            tool_msgs_for_history.append(
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
            tool_msgs_for_history.append(
                {"role": "tool", "tool_call_id": cid, "content": output}
            )

        history.extend(tool_msgs_for_history)
        # then loop — next provider.stream call gets the tool results.

    await logbus.emit("WARN", "agent.loop", f"hit max_iterations={cap}")
    yield StreamWire.frame("error", message=f"hit max_iterations={cap}")


def _to_provider_msg(m: ChatMessage) -> dict[str, Any]:
    """Best-effort conversion of stored ChatMessage → provider wire format."""
    if m.role in ("user", "assistant", "system"):
        return {"role": m.role, "content": m.content}
    # Tool messages are stored as separate `tool_call` and `tool_result`
    # rows; replay them in the same OpenAI-style shape the loop emits.
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
        return {"role": "tool", "tool_call_id": m.tool_result.id, "content": m.tool_result.output}
    return {"role": m.role, "content": m.content}
