"""Process-level settings, read once from env."""

from __future__ import annotations

import os
from pathlib import Path
from typing import Final

from pydantic import BaseModel


def _path(value: str | None, default: str) -> Path:
    return Path(value or default).expanduser().resolve()


class Settings(BaseModel):
    token: str
    host: str = "0.0.0.0"
    port: int = 8765
    workspace_root: Path
    apk_root: Path
    log_file: Path
    default_max_iterations: int = 100


def load_settings() -> Settings:
    token = os.environ.get("SUZU_TOKEN")
    if not token:
        # Allow first-boot before install.sh ran — generate something usable
        # so health checks still work. Real deployments must set the token.
        token = "dev-token-please-change"
    return Settings(
        token=token,
        host=os.environ.get("SUZU_HOST", "0.0.0.0"),
        port=int(os.environ.get("SUZU_PORT", "8765")),
        workspace_root=_path(os.environ.get("SUZU_WORKSPACE_ROOT"), "./workspace"),
        apk_root=_path(os.environ.get("SUZU_APK_ROOT"), "./apk_artifacts"),
        log_file=_path(os.environ.get("SUZU_LOG_FILE"), "./logs/suzu.log"),
        default_max_iterations=int(os.environ.get("SUZU_DEFAULT_MAX_ITERATIONS", "100")),
    )


settings: Final[Settings] = load_settings()
