"""APK toolkit. Runs apktool / apksigner on the SSH host so the heavy IO + JVM
work happens close to the user's storage. After every successful rebuild/sign
we copy the artifact back to the backend's apk_root so the panel can list it.

Each artifact is recorded in `apk_root/index.json` so the /admin/apks endpoint
doesn't have to walk the filesystem.
"""

from __future__ import annotations

import asyncio
import json
import os
import time
import uuid
from pathlib import Path
from typing import Any, TYPE_CHECKING

from ..config import settings
from ..schemas import ApkArtifact
from ..ssh import SshError, SshExec

if TYPE_CHECKING:
    from . import ToolContext


def _index_path() -> Path:
    return settings.apk_root / "index.json"


def list_artifacts() -> list[ApkArtifact]:
    p = _index_path()
    if not p.exists():
        return []
    try:
        raw = json.loads(p.read_text())
    except (OSError, json.JSONDecodeError):
        return []
    return [ApkArtifact(**r) for r in raw]


def _save_index(items: list[ApkArtifact]) -> None:
    settings.apk_root.mkdir(parents=True, exist_ok=True)
    _index_path().write_text(json.dumps([i.model_dump() for i in items], indent=2))


def artifact_file(artifact_id: str) -> Path | None:
    for a in list_artifacts():
        if a.id == artifact_id:
            return settings.apk_root / f"{a.id}__{a.name}"
    return None


def delete_artifact(artifact_id: str) -> bool:
    items = list_artifacts()
    keep = [a for a in items if a.id != artifact_id]
    if len(keep) == len(items):
        return False
    target = settings.apk_root / f"{artifact_id}__"
    for f in settings.apk_root.glob(f"{artifact_id}__*"):
        try:
            f.unlink()
        except OSError:
            pass
    _save_index(keep)
    return True


def _register(name: str, kind: str, session_id: str | None, remote_path: str, ssh: SshExec) -> ApkArtifact:
    """Pull the artifact from the SSH host into apk_root and write index.json."""
    settings.apk_root.mkdir(parents=True, exist_ok=True)
    artifact_id = uuid.uuid4().hex[:10]
    local = settings.apk_root / f"{artifact_id}__{name}"
    ssh.fetch(remote_path, str(local))
    a = ApkArtifact(
        id=artifact_id,
        name=name,
        kind=kind,
        session_id=session_id,
        size=local.stat().st_size,
        created_at=time.time(),
    )
    items = list_artifacts()
    items.append(a)
    _save_index(items)
    return a


async def decompile(ctx: "ToolContext", args: dict[str, Any]) -> str:
    inp = args.get("input", "")
    if not inp:
        return "error: missing 'input'"
    label = args.get("label", Path(inp).stem)
    out_dir = f"{ctx.ssh.workspace}/decompile_{uuid.uuid4().hex[:6]}_{label}"
    ssh = SshExec(ctx.ssh)
    try:
        r = await asyncio.to_thread(
            ssh.run,
            f"mkdir -p {out_dir!r} && apktool d -f -o {out_dir!r} {inp!r}",
            timeout=900,
        )
    except SshError as e:
        return f"ssh-error: {e}"
    if not r.ok:
        return f"apktool failed (exit={r.code}):\n{r.stderr or r.stdout}"
    return f"decompiled to: {out_dir}\n\n{r.stdout.strip()}"


async def recompile(ctx: "ToolContext", args: dict[str, Any]) -> str:
    inp = args.get("input_dir", "")
    output = args.get("output", "")
    if not inp or not output:
        return "error: need both 'input_dir' and 'output'"
    ssh = SshExec(ctx.ssh)
    try:
        r = await asyncio.to_thread(
            ssh.run,
            f"apktool b -f -o {output!r} {inp!r}",
            timeout=900,
        )
    except SshError as e:
        return f"ssh-error: {e}"
    if not r.ok:
        return f"apktool b failed (exit={r.code}):\n{r.stderr or r.stdout}"
    try:
        artifact = await asyncio.to_thread(
            _register,
            Path(output).name,
            "recompiled",
            ctx.session_id,
            output,
            ssh,
        )
    except (SshError, OSError) as e:
        return f"recompile ok but copy back failed: {e}"
    ctx.logbus.info("tool.apk", f"recompiled {artifact.name} (id={artifact.id})")
    return f"recompiled and registered as {artifact.id} ({artifact.name})\n\n{r.stdout.strip()}"


async def sign(ctx: "ToolContext", args: dict[str, Any]) -> str:
    apk = args.get("apk", "")
    if not apk:
        return "error: missing 'apk'"
    workspace = ctx.ssh.workspace or "~"
    ks = f"{workspace}/.luminaris-debug.keystore"
    cn = "CN=Luminaris,OU=Suzu,O=Luminaris,C=US"
    keytool_cmd = (
        f"[ -f {ks!r} ] || keytool -genkeypair -v -keystore {ks!r} -alias luminaris "
        f"-storepass luminaris -keypass luminaris -dname {cn!r} -keyalg RSA -keysize 2048 -validity 365 -storetype PKCS12"
    )
    align_cmd = (
        f"zipalign -p -f 4 {apk!r} {apk!r}.aligned && mv {apk!r}.aligned {apk!r} || true"
    )
    sign_cmd = (
        f"apksigner sign --ks {ks!r} --ks-pass pass:luminaris --key-pass pass:luminaris "
        f"--ks-key-alias luminaris {apk!r}"
    )
    ssh = SshExec(ctx.ssh)
    try:
        for c in (keytool_cmd, align_cmd, sign_cmd):
            r = await asyncio.to_thread(ssh.run, c, timeout=600)
            if not r.ok and "apksigner" in c:
                return f"apksigner failed (exit={r.code}):\n{r.stderr or r.stdout}"
        artifact = await asyncio.to_thread(
            _register,
            Path(apk).name,
            "signed",
            ctx.session_id,
            apk,
            ssh,
        )
    except SshError as e:
        return f"ssh-error: {e}"
    ctx.logbus.info("tool.apk", f"signed {artifact.name} (id={artifact.id})")
    return f"signed and registered as {artifact.id} ({artifact.name})"
