"""Bearer-token auth dependency."""

from __future__ import annotations

from fastapi import Header, HTTPException, status

from .config import settings


async def require_token(authorization: str | None = Header(default=None)) -> None:
    if not authorization or not authorization.lower().startswith("bearer "):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "missing bearer token")
    token = authorization.split(" ", 1)[1].strip()
    if token != settings.token:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "invalid token")
