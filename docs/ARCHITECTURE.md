# SuzuAI Luminaris — Architecture

The product is intentionally split across three deliverables:

```
┌──────────────────────┐                ┌──────────────────────────────┐
│ Panel APK            │   /v1/admin/*  │  FastAPI backend             │
│ (admin / config)     │ ◄────────────► │                              │
└──────────────────────┘                │  - SQLite (config + chats)   │
                                        │  - SSH executor              │
┌──────────────────────┐   /v1/chat     │  - Provider router           │
│ Client APK           │   (SSE)        │  - APK toolkit               │
│ (Devin-style UI)     │ ◄────────────► │                              │
└──────────────────────┘                │  - Log fanout (SSE)          │
                                        └──────────────────────────────┘
```

## Why two APKs

Operationally:

- **Panel APK** holds all credentials (SSH password, AI API key). It is the
  trust boundary — only people with the panel installed can change config.
- **Client APK** never sees credentials directly. It only knows the bearer
  token to talk to the backend, and the backend gates every tool call with the
  config the panel uploaded.

This means you can distribute the client APK to teammates without giving them
your SSH or AI keys.

## Inter-APK contract

| Endpoint | Used by | What it does |
|---|---|---|
| `GET /v1/health` | both | Liveness probe. |
| `GET /v1/ready` | client | Returns `{"ready": bool, "missing": [...]}`. Client refuses to chat unless `ready=true`. |
| `GET/PUT /v1/admin/config` | panel | The whole config blob (SSH + provider). PUT requires panel-role token. |
| `POST /v1/admin/config/verify` | panel | Dry-run: SSH connect + LLM hello. Returns per-row status. |
| `GET /v1/admin/logs/stream` | panel | SSE stream of recent backend logs. |
| `GET /v1/admin/apks` | panel | List of APK artifacts (decompiled / recompiled / signed). |
| `GET /v1/admin/apks/{id}/download` | panel | Binary download of an artifact. |
| `GET/POST /v1/sessions` | client | List + create chat sessions. |
| `POST /v1/chat` (SSE) | client | The streaming agent loop. Same event envelope as Suzu_Ai. |

## Agent loop

Identical contract to Anthropic "tool use":

1. Client posts a user message to `/v1/chat`.
2. Backend assembles the conversation, calls the provider (Anthropic native
   or OpenAI-compatible), streams `delta` events.
3. When the model emits a `tool_use` block, backend executes the tool (shell
   over SSH, file read/write on the SSH box, APK toolkit) and emits
   `tool_call` + `tool_result` events.
4. Loop until `stop_reason=end_turn` or `max_iterations`.

## SSH boundary

All shell + file tools run **on the SSH box**, not on the FastAPI host. The
backend opens a long-lived SSH session per chat. The FastAPI host only needs
Python + the APK toolkit (apktool / jadx / apksigner); everything else
happens on the user's own VPS via SSH.

## APK toolkit

`server/suzu_luminaris/tools/apk.py` wraps `apktool d`, `apktool b`, and
`apksigner sign` with sane defaults. Output APKs are written to
`server/apk_artifacts/{session_id}/{name}.apk` and indexed in SQLite so the
Panel APK can list / download them.

## Logs

Every important event (agent step, tool call, SSH command, APK build) is
appended to a ring buffer + persisted to `server/logs/suzu.log`. The Panel
APK subscribes to `GET /v1/admin/logs/stream` (SSE) and renders live.
