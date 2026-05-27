"""Paramiko-backed SSH executor.

Every shell / file tool used by the agent runs through SshExec. The class is
intentionally thin: open a session, run a command, return stdout/stderr/code.
We re-dial on every call so a stale connection doesn't take a whole session
down — the panel can swap SSH credentials live and the next tool call picks
them up.
"""

from __future__ import annotations

import io
from dataclasses import dataclass
from typing import Any

import paramiko

from .schemas import SshConfig


@dataclass
class RunResult:
    code: int
    stdout: str
    stderr: str

    @property
    def ok(self) -> bool:
        return self.code == 0


class SshError(RuntimeError):
    pass


class SshExec:
    """One-shot SSH client. Construct, run, dispose."""

    def __init__(self, cfg: SshConfig):
        self.cfg = cfg

    def _client(self) -> paramiko.SSHClient:
        if not self.cfg.host:
            raise SshError("SSH host is empty — fill it in the panel app first")

        client = paramiko.SSHClient()
        client.set_missing_host_key_policy(paramiko.AutoAddPolicy())

        kwargs: dict[str, Any] = dict(
            hostname=self.cfg.host,
            port=self.cfg.port,
            username=self.cfg.user,
            timeout=15,
            banner_timeout=15,
            auth_timeout=20,
            allow_agent=False,
            look_for_keys=False,
        )

        if self.cfg.auth_mode == "key" and self.cfg.private_key:
            pkey = paramiko.RSAKey.from_private_key(io.StringIO(self.cfg.private_key))
            kwargs["pkey"] = pkey
        else:
            if not self.cfg.password:
                raise SshError("SSH password is empty")
            kwargs["password"] = self.cfg.password

        client.connect(**kwargs)
        return client

    def run(self, cmd: str, timeout: int = 300) -> RunResult:
        """Run a single command via `bash -lc`. cwd defaults to ssh.workspace."""
        workspace = self.cfg.workspace or "~"
        wrapped = (
            f"mkdir -p {workspace} >/dev/null 2>&1 || true; "
            f"cd {workspace} && bash -lc {_q(cmd)}"
        )
        client = self._client()
        try:
            stdin, stdout, stderr = client.exec_command(wrapped, timeout=timeout)
            stdin.close()
            out = stdout.read().decode("utf-8", errors="replace")
            err = stderr.read().decode("utf-8", errors="replace")
            code = stdout.channel.recv_exit_status()
            return RunResult(code=code, stdout=out, stderr=err)
        finally:
            client.close()

    def read_file(self, path: str, max_bytes: int = 1_000_000) -> str:
        cmd = f"head -c {max_bytes} {_q(path)}"
        r = self.run(cmd)
        if not r.ok:
            raise SshError(f"read_file({path}) failed: {r.stderr.strip() or r.code}")
        return r.stdout

    def write_file(self, path: str, content: str) -> None:
        # Use base64 to safely transmit arbitrary content over `bash -lc`.
        import base64

        b64 = base64.b64encode(content.encode("utf-8")).decode("ascii")
        cmd = (
            f"mkdir -p \"$(dirname {_q(path)})\" && "
            f"echo {_q(b64)} | base64 -d > {_q(path)}"
        )
        r = self.run(cmd)
        if not r.ok:
            raise SshError(f"write_file({path}) failed: {r.stderr.strip() or r.code}")

    def fetch(self, remote_path: str, local_path: str) -> None:
        client = self._client()
        try:
            sftp = client.open_sftp()
            try:
                sftp.get(remote_path, local_path)
            finally:
                sftp.close()
        finally:
            client.close()


def _q(s: str) -> str:
    """Single-quote a string for safe shell embedding."""
    return "'" + s.replace("'", "'\"'\"'") + "'"


def verify(cfg: SshConfig) -> str:
    """Returns a single-line summary or raises SshError."""
    r = SshExec(cfg).run("uname -a && id", timeout=15)
    if not r.ok:
        raise SshError(r.stderr.strip() or f"exit {r.code}")
    return r.stdout.strip().splitlines()[0] if r.stdout else "connected"
