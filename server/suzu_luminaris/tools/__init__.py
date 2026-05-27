"""Tools the agent can call. Each tool returns plain text (truncated to ~32 KB)
so it slots into a tool_result wire event without any further processing."""

from __future__ import annotations

import asyncio
import json
from dataclasses import dataclass
from typing import Any, Callable, Awaitable

from ..logbus import LogBus
from ..schemas import SshConfig
from ..ssh import SshError, SshExec
from . import apk as apk_tool
from . import filetool, shelltool, webtool

MAX_OUTPUT = 32_000


@dataclass
class ToolContext:
    ssh: SshConfig
    session_id: str
    logbus: LogBus


@dataclass
class ToolSpec:
    name: str
    description: str
    parameters: dict[str, Any]
    run: Callable[[ToolContext, dict[str, Any]], Awaitable[str]]


def _truncate(s: str) -> str:
    if len(s) <= MAX_OUTPUT:
        return s
    return s[:MAX_OUTPUT] + f"\n…[truncated, {len(s) - MAX_OUTPUT} more bytes]"


async def _ssh_exec(ctx: ToolContext) -> SshExec:
    return SshExec(ctx.ssh)


async def _shell(ctx: ToolContext, args: dict[str, Any]) -> str:
    cmd = args.get("command", "")
    timeout = int(args.get("timeout", 300))
    if not cmd:
        return "error: missing 'command'"
    ctx.logbus.info("tool.shell", f"$ {cmd}")
    try:
        ssh = await _ssh_exec(ctx)
        r = await asyncio.to_thread(ssh.run, cmd, timeout)
    except SshError as e:
        return f"ssh-error: {e}"
    body = (r.stdout + (("\n[stderr]\n" + r.stderr) if r.stderr else "")).strip()
    return _truncate(f"exit={r.code}\n{body}")


async def _read_file(ctx: ToolContext, args: dict[str, Any]) -> str:
    path = args.get("path", "")
    if not path:
        return "error: missing 'path'"
    try:
        ssh = await _ssh_exec(ctx)
        return _truncate(await asyncio.to_thread(ssh.read_file, path))
    except SshError as e:
        return f"ssh-error: {e}"


async def _write_file(ctx: ToolContext, args: dict[str, Any]) -> str:
    path = args.get("path", "")
    content = args.get("content", "")
    if not path:
        return "error: missing 'path'"
    try:
        ssh = await _ssh_exec(ctx)
        await asyncio.to_thread(ssh.write_file, path, content)
        return f"wrote {len(content)} bytes to {path}"
    except SshError as e:
        return f"ssh-error: {e}"


async def _list_dir(ctx: ToolContext, args: dict[str, Any]) -> str:
    path = args.get("path", ".")
    try:
        ssh = await _ssh_exec(ctx)
        r = await asyncio.to_thread(ssh.run, f"ls -lah {path!r}", 30)
    except SshError as e:
        return f"ssh-error: {e}"
    return _truncate(r.stdout or r.stderr or f"exit={r.code}")


async def _apk_decompile(ctx: ToolContext, args: dict[str, Any]) -> str:
    return _truncate(await apk_tool.decompile(ctx, args))


async def _apk_recompile(ctx: ToolContext, args: dict[str, Any]) -> str:
    return _truncate(await apk_tool.recompile(ctx, args))


async def _apk_sign(ctx: ToolContext, args: dict[str, Any]) -> str:
    return _truncate(await apk_tool.sign(ctx, args))


async def _web_get(ctx: ToolContext, args: dict[str, Any]) -> str:
    url = args.get("url", "")
    if not url:
        return "error: missing 'url'"
    return _truncate(await webtool.fetch(url))


SPECS: list[ToolSpec] = [
    ToolSpec(
        name="shell",
        description="Run a bash command on the remote SSH host. Returns stdout/stderr/exit code.",
        parameters={
            "type": "object",
            "properties": {
                "command": {"type": "string"},
                "timeout": {"type": "integer", "default": 300},
            },
            "required": ["command"],
        },
        run=_shell,
    ),
    ToolSpec(
        name="read_file",
        description="Read a file on the remote host (up to ~1 MB).",
        parameters={
            "type": "object",
            "properties": {"path": {"type": "string"}},
            "required": ["path"],
        },
        run=_read_file,
    ),
    ToolSpec(
        name="write_file",
        description="Write (overwrite) a UTF-8 file on the remote host.",
        parameters={
            "type": "object",
            "properties": {
                "path": {"type": "string"},
                "content": {"type": "string"},
            },
            "required": ["path", "content"],
        },
        run=_write_file,
    ),
    ToolSpec(
        name="list_dir",
        description="List the contents of a directory on the remote host.",
        parameters={
            "type": "object",
            "properties": {"path": {"type": "string", "default": "."}},
        },
        run=_list_dir,
    ),
    ToolSpec(
        name="apk_decompile",
        description="Decompile an APK with apktool, save output under workspace and register it.",
        parameters={
            "type": "object",
            "properties": {
                "input": {"type": "string", "description": "Remote path to the input .apk"},
                "label": {"type": "string", "description": "Optional human label"},
            },
            "required": ["input"],
        },
        run=_apk_decompile,
    ),
    ToolSpec(
        name="apk_recompile",
        description="Rebuild a decompiled directory back into an unsigned APK.",
        parameters={
            "type": "object",
            "properties": {
                "input_dir": {"type": "string", "description": "Remote path to decompiled dir"},
                "output": {"type": "string", "description": "Output APK path"},
            },
            "required": ["input_dir", "output"],
        },
        run=_apk_recompile,
    ),
    ToolSpec(
        name="apk_sign",
        description="Sign a (re)built APK with a debug keystore and register it as installable.",
        parameters={
            "type": "object",
            "properties": {"apk": {"type": "string"}},
            "required": ["apk"],
        },
        run=_apk_sign,
    ),
    ToolSpec(
        name="web_get",
        description="HTTP GET a URL from the backend's network and return the response body (truncated).",
        parameters={
            "type": "object",
            "properties": {"url": {"type": "string"}},
            "required": ["url"],
        },
        run=_web_get,
    ),
]


def schema_for_provider() -> list[dict[str, Any]]:
    return [
        {"name": t.name, "description": t.description, "parameters": t.parameters}
        for t in SPECS
    ]


def by_name(name: str) -> ToolSpec | None:
    for t in SPECS:
        if t.name == name:
            return t
    return None


def parse_args(raw: str) -> dict[str, Any]:
    try:
        return json.loads(raw or "{}")
    except json.JSONDecodeError:
        return {"_raw": raw}
