"""FastAPI entrypoint.

Endpoints (all under bearer token, no auth on /v1/health):

  GET  /v1/health                — liveness + version
  GET  /v1/ready                 — true iff config has ssh.host + ai.base_url + model + api_key

  GET  /v1/admin/config          — load AdminConfig
  PUT  /v1/admin/config          — save AdminConfig
  DELETE /v1/admin/config        — wipe AdminConfig (fresh start)
  POST /v1/admin/config/verify   — dry-run: ssh `uname -a`, llm `ping`

  GET  /v1/admin/logs/stream     — SSE of LogEvent
  GET  /v1/admin/apks            — list ApkArtifact
  GET  /v1/admin/apks/{id}/download — stream the apk file
  DELETE /v1/admin/apks/{id}     — delete an artifact

  GET  /v1/admin/backup/export   — zip download of full backend state
  POST /v1/admin/backup/import   — multipart upload restoring state

  GET  /v1/sessions              — list
  POST /v1/sessions              — create
  DELETE /v1/sessions/{id}       — delete
  GET  /v1/sessions/{id}/messages — list ChatMessage

  POST /v1/chat                  — SSE stream of agent loop (StreamEvent)
"""

from __future__ import annotations

import asyncio
import json
from contextlib import asynccontextmanager
from typing import Any

from fastapi import (
    Depends,
    FastAPI,
    File,
    HTTPException,
    Request,
    Response,
    UploadFile,
    status,
)
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, StreamingResponse

from . import agent, backup, providers
from .auth import require_token
from .config import settings
from .db import Db
from .logbus import LogBus
from .schemas import (
    AdminConfig,
    ApkArtifact,
    ChatMessage,
    ChatRequest,
    EventsResponse,
    HealthResponse,
    ReadyResponse,
    Session,
    VerifyResponse,
    VerifyRow,
)
from .ssh import SshError
from .tools import apk as apk_tool


async def require_ready() -> None:
    """Client-facing gate: only blocks if the panel hasn't saved a config yet.

    Once the panel writes a complete AdminConfig, every Client APK on the same
    LAN that knows the URL can chat — by design. The admin endpoints stay
    bearer-token-gated.
    """
    cfg = await db.get_config()
    if not cfg.ai_provider.api_key or not cfg.ai_provider.model or not cfg.ssh.host:
        raise HTTPException(
            status.HTTP_503_SERVICE_UNAVAILABLE,
            "backend not configured — ask the Panel app operator to fill SSH + AI provider config first",
        )

VERSION = "0.1.0"

db = Db()
logbus = LogBus(settings.log_file)


@asynccontextmanager
async def lifespan(_: FastAPI):
    await db.connect()
    await logbus.emit("INFO", "boot", f"luminaris {VERSION} bound to {settings.host}:{settings.port}")
    try:
        yield
    finally:
        await db.close()


app = FastAPI(title="SuzuAI Luminaris", version=VERSION, lifespan=lifespan)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


# -------- health / ready (no auth on health, auth on ready) -----------

@app.get("/v1/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    return HealthResponse(status="ok", version=VERSION)


@app.get("/v1/ready", response_model=ReadyResponse)
async def ready() -> ReadyResponse:
    """Public — clients only need to know if the panel finished configuring."""
    cfg = await db.get_config()
    missing: list[str] = []
    if not cfg.ssh.host:
        missing.append("ssh.host")
    if cfg.ssh.auth_mode == "password" and not cfg.ssh.password:
        missing.append("ssh.password")
    if cfg.ssh.auth_mode == "key" and not cfg.ssh.private_key:
        missing.append("ssh.private_key")
    if not cfg.ai_provider.model:
        missing.append("ai_provider.model")
    if not cfg.ai_provider.api_key:
        missing.append("ai_provider.api_key")
    return ReadyResponse(ready=len(missing) == 0, missing=missing)


# -------- admin/config ------------------------------------------------

@app.get("/v1/admin/config", response_model=AdminConfig, dependencies=[Depends(require_token)])
async def get_config() -> AdminConfig:
    return await db.get_config()


@app.put("/v1/admin/config", response_model=AdminConfig, dependencies=[Depends(require_token)])
async def put_config(cfg: AdminConfig) -> AdminConfig:
    out = await db.put_config(cfg)
    await logbus.emit("INFO", "admin.config", "config updated")
    return out


@app.delete("/v1/admin/config", response_model=AdminConfig, dependencies=[Depends(require_token)])
async def wipe_config() -> AdminConfig:
    out = await db.wipe_config()
    await logbus.emit("WARN", "admin.config", "config wiped")
    return out


@app.post(
    "/v1/admin/config/verify",
    response_model=VerifyResponse,
    dependencies=[Depends(require_token)],
)
async def verify_config() -> VerifyResponse:
    cfg = await db.get_config()
    rows: list[VerifyRow] = []

    # SSH
    try:
        from .ssh import verify as ssh_verify

        summary = await asyncio.to_thread(ssh_verify, cfg.ssh)
        rows.append(VerifyRow(name="ssh", ok=True, detail=summary))
    except (SshError, Exception) as e:  # noqa: BLE001
        rows.append(VerifyRow(name="ssh", ok=False, detail=str(e)[:300]))

    # LLM
    try:
        detail = await providers.verify(cfg.ai_provider)
        rows.append(VerifyRow(name="ai_provider", ok=True, detail=detail))
    except (providers.ProviderError, Exception) as e:  # noqa: BLE001
        rows.append(VerifyRow(name="ai_provider", ok=False, detail=str(e)[:300]))

    return VerifyResponse(rows=rows)


# -------- admin/logs --------------------------------------------------

@app.get("/v1/admin/logs/stream", dependencies=[Depends(require_token)])
async def stream_logs(request: Request) -> StreamingResponse:
    q = await logbus.subscribe()

    async def gen():
        try:
            while True:
                if await request.is_disconnected():
                    break
                try:
                    ev = await asyncio.wait_for(q.get(), timeout=15.0)
                    yield f"data: {json.dumps(ev.model_dump())}\n\n"
                except asyncio.TimeoutError:
                    yield ": keepalive\n\n"
        finally:
            await logbus.unsubscribe(q)

    return StreamingResponse(gen(), media_type="text/event-stream")


# -------- admin/apks --------------------------------------------------

@app.get("/v1/admin/apks", response_model=list[ApkArtifact], dependencies=[Depends(require_token)])
async def list_apks() -> list[ApkArtifact]:
    return apk_tool.list_artifacts()


@app.get("/v1/admin/apks/{artifact_id}/download", dependencies=[Depends(require_token)])
async def download_apk(artifact_id: str) -> FileResponse:
    path = apk_tool.artifact_file(artifact_id)
    if path is None or not path.exists():
        raise HTTPException(status.HTTP_404_NOT_FOUND, "artifact not found")
    return FileResponse(path, media_type="application/vnd.android.package-archive", filename=path.name.split("__", 1)[-1])


@app.delete("/v1/admin/apks/{artifact_id}", dependencies=[Depends(require_token)])
async def delete_apk(artifact_id: str) -> dict[str, Any]:
    ok = apk_tool.delete_artifact(artifact_id)
    if not ok:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "artifact not found")
    return {"deleted": artifact_id}


# -------- admin/backup ------------------------------------------------

@app.get("/v1/admin/backup/export", dependencies=[Depends(require_token)])
async def export_backup() -> Response:
    data = await backup.export_zip(db)
    return Response(
        content=data,
        media_type="application/zip",
        headers={"Content-Disposition": 'attachment; filename="luminaris-bundle.zip"'},
    )


@app.post("/v1/admin/backup/import", dependencies=[Depends(require_token)])
async def import_backup(file: UploadFile = File(...)) -> dict[str, Any]:
    raw = await file.read()
    try:
        counts = await backup.import_zip(db, raw)
    except (ValueError, KeyError) as e:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, f"bad bundle: {e}") from e
    await logbus.emit("WARN", "admin.backup", f"restored bundle (sessions={counts['sessions']}, apks={counts['apks']})")
    return {"ok": True, **counts}


# -------- sessions ----------------------------------------------------

@app.get("/v1/sessions", response_model=list[Session], dependencies=[Depends(require_ready)])
async def list_sessions() -> list[Session]:
    return await db.list_sessions()


@app.post("/v1/sessions", response_model=Session, dependencies=[Depends(require_ready)])
async def create_session(payload: dict[str, Any]) -> Session:
    title = (payload.get("title") or "Untitled").strip() or "Untitled"
    return await db.create_session(title)


@app.delete("/v1/sessions/{sid}", dependencies=[Depends(require_ready)])
async def delete_session(sid: str) -> dict[str, str]:
    await db.delete_session(sid)
    return {"deleted": sid}


@app.get(
    "/v1/sessions/{sid}/messages",
    response_model=list[ChatMessage],
    dependencies=[Depends(require_ready)],
)
async def list_messages(sid: str) -> list[ChatMessage]:
    return await db.list_messages(sid)


# -------- chat (background + event polling) ----------------------------

@app.post("/v1/chat", dependencies=[Depends(require_ready)])
async def chat(req: ChatRequest) -> dict[str, Any]:
    """Kicks off the agent loop as a background task and returns immediately.

    The client should then long-poll `/v1/sessions/{id}/events?since=<seq>`
    for incremental updates. This survives the client closing the APK —
    the agent keeps running and persisting events.
    """
    cfg = await db.get_config()
    if not cfg.ai_provider.api_key or not cfg.ai_provider.model:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "ai provider not configured")
    if not cfg.ssh.host:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "ssh not configured")
    if agent.is_running(req.session_id):
        raise HTTPException(
            status.HTTP_409_CONFLICT,
            "session already has a running agent — wait for it to finish",
        )
    sess = await db.get_session(req.session_id)
    if sess is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "unknown session")
    try:
        await agent.start(
            db=db,
            cfg=cfg,
            logbus=logbus,
            session_id=req.session_id,
            user_message=req.content,
            max_iterations=req.max_iterations,
        )
    except RuntimeError as e:
        raise HTTPException(status.HTTP_409_CONFLICT, str(e)) from e
    return {"started": True, "session_id": req.session_id}


@app.get(
    "/v1/sessions/{sid}/events",
    response_model=EventsResponse,
    dependencies=[Depends(require_ready)],
)
async def session_events(
    sid: str,
    since: int = 0,
    timeout: float = 25.0,
    request: Request = None,  # type: ignore[assignment]
) -> EventsResponse:
    """Long-poll for agent events with seq > since.

    Returns immediately if there are already pending events, otherwise
    waits up to `timeout` seconds for the next notify from the agent
    loop. Capped at 25s by default to play nice with mobile data savers.
    """
    sess = await db.get_session(sid)
    if sess is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "unknown session")

    events = await db.list_events(sid, since)
    if not events:
        ev = agent.bus.get(sid)
        try:
            await asyncio.wait_for(ev.wait(), timeout=max(1.0, min(timeout, 60.0)))
        except asyncio.TimeoutError:
            pass
        # Re-check whether the client gave up while we were waiting.
        if request is not None and await request.is_disconnected():
            return EventsResponse(
                session_id=sid,
                status=sess.status,
                tokens_in=sess.tokens_in,
                tokens_out=sess.tokens_out,
                events=[],
                last_seq=since,
                running=agent.is_running(sid),
            )
        events = await db.list_events(sid, since)

    sess = await db.get_session(sid)
    last_seq = events[-1].seq if events else since
    return EventsResponse(
        session_id=sid,
        status=sess.status if sess else "idle",
        tokens_in=sess.tokens_in if sess else 0,
        tokens_out=sess.tokens_out if sess else 0,
        events=events,
        last_seq=last_seq,
        running=agent.is_running(sid),
    )
