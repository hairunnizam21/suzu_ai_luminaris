"""Backup / transfer bundle. Lets the panel app export an entire backend
state as a single zip and import it on a new VPS.

Bundle layout::

    luminaris-bundle.zip
    ├── manifest.json     # version, generated_at, source_host
    ├── config.json       # AdminConfig (incl. secrets)
    ├── sessions.db       # SQLite snapshot
    ├── apk_artifacts/    # zipped APK files (only the ones in index.json)
    │   └── *.apk
    └── apk_index.json    # parallel to apk_artifacts/index.json

import semantics: REPLACE everything. We refuse to merge — too easy to corrupt
state. The caller is expected to confirm before tapping Import.
"""

from __future__ import annotations

import io
import json
import shutil
import time
import zipfile
from pathlib import Path

from .config import settings
from .db import Db
from .schemas import AdminConfig

VERSION = "1"


async def export_zip(db: Db) -> bytes:
    cfg = await db.get_config()
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr(
            "manifest.json",
            json.dumps(
                {
                    "version": VERSION,
                    "generated_at": time.time(),
                    "source": "luminaris",
                },
                indent=2,
            ),
        )
        zf.writestr("config.json", cfg.model_dump_json(indent=2))

        # SQLite snapshot via VACUUM INTO so we don't fight WAL.
        snap_path = settings.workspace_root / f".bundle-{int(time.time())}.db"
        snap_path.parent.mkdir(parents=True, exist_ok=True)
        await db.conn.execute(f"VACUUM INTO '{snap_path}'")
        zf.writestr("sessions.db", snap_path.read_bytes())
        snap_path.unlink(missing_ok=True)

        # APK index + files.
        idx = settings.apk_root / "index.json"
        if idx.exists():
            zf.writestr("apk_index.json", idx.read_text())
            for f in settings.apk_root.glob("*__*"):
                zf.write(f, arcname=f"apk_artifacts/{f.name}")

    return buf.getvalue()


async def import_zip(db: Db, data: bytes) -> dict[str, int]:
    """Restore. Returns counts so the UI can show "restored 12 sessions, 3 APKs"."""
    with zipfile.ZipFile(io.BytesIO(data)) as zf:
        manifest = json.loads(zf.read("manifest.json"))
        if manifest.get("version") != VERSION:
            raise ValueError(f"unsupported bundle version: {manifest.get('version')!r}")

        cfg = AdminConfig.model_validate_json(zf.read("config.json"))
        await db.put_config(cfg)

        # Replace the live DB by copying the snapshot over and reconnecting.
        snap_bytes = zf.read("sessions.db")
        await db.close()
        db.path.write_bytes(snap_bytes)
        await db.connect()

        # APKs.
        settings.apk_root.mkdir(parents=True, exist_ok=True)
        # wipe existing
        for f in settings.apk_root.glob("*__*"):
            try:
                f.unlink()
            except OSError:
                pass

        n_apk = 0
        for name in zf.namelist():
            if name.startswith("apk_artifacts/"):
                with zf.open(name) as src, (settings.apk_root / Path(name).name).open("wb") as dst:
                    shutil.copyfileobj(src, dst)
                    n_apk += 1
        if "apk_index.json" in zf.namelist():
            (settings.apk_root / "index.json").write_bytes(zf.read("apk_index.json"))

    sessions = await db.list_sessions()
    return {"sessions": len(sessions), "apks": n_apk}
