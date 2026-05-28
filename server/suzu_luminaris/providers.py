"""LLM provider adapters.

Two shapes:
  * openai_compat / openai → POST /chat/completions with `stream=True`.
  * anthropic → POST /v1/messages with `stream=True`.

Both yield small dicts with `type`: "delta" | "tool_call" | "stop" | "error".
The agent loop normalises these into the same wire SSE shape.

For tool use we use OpenAI-style `tools` schema with both providers — most
OpenAI-compatible gateways speak it; anthropic-native is mapped on the fly.
"""

from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator
from typing import Any

import httpx

from .schemas import AiProviderConfig

# If the upstream goes silent for this long mid-stream we treat the request as
# dead and surface a retryable error. fiqstr / OpenRouter / similar gateways
# sometimes hang on heavy outputs without ever sending a closing chunk.
_STREAM_IDLE_TIMEOUT_S = 60.0


class ProviderError(RuntimeError):
    pass


def _format_error(status_code: int, body: bytes) -> str:
    """Extract a human-readable message from a provider error response."""
    text = body[:400].decode("utf-8", "replace")
    try:
        obj = json.loads(text)
        if isinstance(obj, dict):
            # OpenAI / fiqstr / OpenRouter: {"error":{"message":"..."}}
            err = obj.get("error")
            if isinstance(err, dict):
                msg = err.get("message") or err.get("msg") or ""
                if msg:
                    return f"HTTP {status_code}: {msg}"
            # Anthropic: {"error":{"type":"...","message":"..."}}
            # Simple {"detail":"..."} or {"message":"..."}
            for key in ("detail", "message"):
                if key in obj and isinstance(obj[key], str):
                    return f"HTTP {status_code}: {obj[key]}"
    except (json.JSONDecodeError, KeyError, TypeError):
        pass
    return f"HTTP {status_code}: {text}"


async def _iter_lines_with_idle_timeout(
    resp: httpx.Response, idle_timeout: float
) -> AsyncIterator[str]:
    """Yield SSE lines, abort if no line arrives within `idle_timeout` s."""
    it = resp.aiter_lines().__aiter__()
    while True:
        try:
            line = await asyncio.wait_for(it.__anext__(), timeout=idle_timeout)
        except StopAsyncIteration:
            return
        except asyncio.TimeoutError as e:
            raise ProviderError(
                f"upstream went silent for {idle_timeout:.0f}s (idle timeout)"
            ) from e
        yield line


async def verify(cfg: AiProviderConfig) -> str:
    """Best-effort "hello" round trip used by /admin/config/verify."""
    async with httpx.AsyncClient(timeout=30) as http:
        if cfg.kind == "anthropic":
            r = await http.post(
                _join(cfg.base_url or "https://api.anthropic.com", "/v1/messages"),
                headers={
                    "x-api-key": cfg.api_key,
                    "anthropic-version": "2023-06-01",
                    "content-type": "application/json",
                },
                json={
                    "model": cfg.model,
                    "max_tokens": 16,
                    "messages": [{"role": "user", "content": "ping"}],
                },
            )
        else:
            r = await http.post(
                _join(cfg.base_url or "https://api.openai.com", "/chat/completions"),
                headers={
                    "Authorization": f"Bearer {cfg.api_key}",
                    "Content-Type": "application/json",
                },
                json={
                    "model": cfg.model,
                    "max_tokens": 16,
                    "messages": [{"role": "user", "content": "ping"}],
                },
            )
    if r.status_code >= 400:
        raise ProviderError(_format_error(r.status_code, r.content))
    return f"OK ({r.status_code})"


async def stream(
    cfg: AiProviderConfig,
    messages: list[dict[str, Any]],
    tools: list[dict[str, Any]],
) -> AsyncIterator[dict[str, Any]]:
    """Stream chunks. Yields dicts the agent normalises further."""
    if cfg.kind == "anthropic":
        async for chunk in _stream_anthropic(cfg, messages, tools):
            yield chunk
    else:
        async for chunk in _stream_openai(cfg, messages, tools):
            yield chunk


async def _stream_openai(
    cfg: AiProviderConfig,
    messages: list[dict[str, Any]],
    tools: list[dict[str, Any]],
) -> AsyncIterator[dict[str, Any]]:
    url = _join(cfg.base_url or "https://api.openai.com", "/chat/completions")
    payload: dict[str, Any] = {
        "model": cfg.model,
        "messages": messages,
        "stream": True,
        "temperature": cfg.temperature,
        # Most OpenAI-compatible gateways honour this and emit a final chunk
        # with `usage` populated. Harmless for those that don't.
        "stream_options": {"include_usage": True},
    }
    if cfg.max_tokens > 0:
        payload["max_tokens"] = cfg.max_tokens
    if tools:
        payload["tools"] = [
            {"type": "function", "function": t} for t in tools
        ]
        payload["tool_choice"] = "auto"

    timeout = httpx.Timeout(connect=30, read=None, write=30, pool=30)
    async with httpx.AsyncClient(timeout=timeout) as http:
        async with http.stream(
            "POST",
            url,
            headers={
                "Authorization": f"Bearer {cfg.api_key}",
                "Content-Type": "application/json",
                "Accept": "text/event-stream",
            },
            json=payload,
        ) as resp:
            if resp.status_code >= 400:
                body = await resp.aread()
                raise ProviderError(_format_error(resp.status_code, body))
            # Accumulate tool call args across deltas keyed by index.
            tool_accum: dict[int, dict[str, str]] = {}
            async for line in _iter_lines_with_idle_timeout(resp, _STREAM_IDLE_TIMEOUT_S):
                if not line or not line.startswith("data:"):
                    continue
                raw = line[len("data:") :].strip()
                if raw == "[DONE]":
                    for idx in sorted(tool_accum):
                        call = tool_accum[idx]
                        yield {
                            "type": "tool_call",
                            "id": call.get("id") or f"call_{idx}",
                            "name": call.get("name", ""),
                            "args": call.get("args", ""),
                        }
                    yield {"type": "stop"}
                    return
                try:
                    obj = json.loads(raw)
                except json.JSONDecodeError:
                    continue
                if (usage := obj.get("usage")) and isinstance(usage, dict):
                    yield {
                        "type": "usage",
                        "prompt": int(usage.get("prompt_tokens") or 0),
                        "completion": int(usage.get("completion_tokens") or 0),
                        "total": int(usage.get("total_tokens") or 0),
                    }
                choices = obj.get("choices") or []
                if not choices:
                    continue
                delta = choices[0].get("delta") or {}
                if (content := delta.get("content")):
                    yield {"type": "delta", "text": content}
                for tc in delta.get("tool_calls") or []:
                    idx = tc.get("index", 0)
                    slot = tool_accum.setdefault(idx, {"args": "", "name": ""})
                    if (cid := tc.get("id")):
                        slot["id"] = cid
                    fn = tc.get("function") or {}
                    if (nm := fn.get("name")):
                        slot["name"] = nm
                    if (args := fn.get("arguments")):
                        slot["args"] += args


async def _stream_anthropic(
    cfg: AiProviderConfig,
    messages: list[dict[str, Any]],
    tools: list[dict[str, Any]],
) -> AsyncIterator[dict[str, Any]]:
    url = _join(cfg.base_url or "https://api.anthropic.com", "/v1/messages")
    # Anthropic separates system from messages.
    system = ""
    cleaned: list[dict[str, Any]] = []
    for m in messages:
        if m["role"] == "system":
            system += ("\n" if system else "") + m["content"]
        else:
            cleaned.append(m)

    payload: dict[str, Any] = {
        "model": cfg.model,
        "messages": cleaned,
        "stream": True,
        "max_tokens": cfg.max_tokens if cfg.max_tokens > 0 else 4096,
        "temperature": cfg.temperature,
    }
    if system:
        payload["system"] = system
    if tools:
        payload["tools"] = [
            {
                "name": t["name"],
                "description": t.get("description", ""),
                "input_schema": t.get("parameters", {"type": "object"}),
            }
            for t in tools
        ]

    timeout = httpx.Timeout(connect=30, read=None, write=30, pool=30)
    async with httpx.AsyncClient(timeout=timeout) as http:
        async with http.stream(
            "POST",
            url,
            headers={
                "x-api-key": cfg.api_key,
                "anthropic-version": "2023-06-01",
                "Content-Type": "application/json",
                "Accept": "text/event-stream",
            },
            json=payload,
        ) as resp:
            if resp.status_code >= 400:
                body = await resp.aread()
                raise ProviderError(_format_error(resp.status_code, body))
            tool_accum: dict[int, dict[str, str]] = {}
            async for line in _iter_lines_with_idle_timeout(resp, _STREAM_IDLE_TIMEOUT_S):
                if not line or not line.startswith("data:"):
                    continue
                raw = line[len("data:") :].strip()
                try:
                    obj = json.loads(raw)
                except json.JSONDecodeError:
                    continue
                t = obj.get("type")
                if t == "message_start":
                    mu = ((obj.get("message") or {}).get("usage") or {})
                    if mu:
                        yield {
                            "type": "usage",
                            "prompt": int(mu.get("input_tokens") or 0),
                            "completion": int(mu.get("output_tokens") or 0),
                            "total": int(mu.get("input_tokens") or 0)
                            + int(mu.get("output_tokens") or 0),
                        }
                elif t == "message_delta":
                    mu = obj.get("usage") or {}
                    if mu:
                        yield {
                            "type": "usage",
                            "prompt": 0,
                            "completion": int(mu.get("output_tokens") or 0),
                            "total": int(mu.get("output_tokens") or 0),
                        }
                elif t == "content_block_delta":
                    d = obj.get("delta") or {}
                    if d.get("type") == "text_delta" and (txt := d.get("text")):
                        yield {"type": "delta", "text": txt}
                    elif d.get("type") == "input_json_delta":
                        idx = obj.get("index", 0)
                        slot = tool_accum.setdefault(idx, {"args": "", "name": ""})
                        slot["args"] += d.get("partial_json", "")
                elif t == "content_block_start":
                    cb = obj.get("content_block") or {}
                    if cb.get("type") == "tool_use":
                        idx = obj.get("index", 0)
                        tool_accum[idx] = {
                            "id": cb.get("id", f"call_{idx}"),
                            "name": cb.get("name", ""),
                            "args": "",
                        }
                elif t == "message_stop":
                    for idx in sorted(tool_accum):
                        call = tool_accum[idx]
                        yield {
                            "type": "tool_call",
                            "id": call.get("id") or f"call_{idx}",
                            "name": call.get("name", ""),
                            "args": call.get("args", ""),
                        }
                    yield {"type": "stop"}
                    return


def _join(base: str, path: str) -> str:
    return base.rstrip("/") + "/" + path.lstrip("/")
