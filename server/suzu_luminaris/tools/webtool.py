"""HTTP GET tool. Runs on the backend, not via SSH — agents sometimes need to
grab docs from the public internet even when the SSH workspace has no curl
(or you don't want to leak the API key onto the user's VPS)."""

from __future__ import annotations

import httpx


async def fetch(url: str, timeout: float = 30) -> str:
    async with httpx.AsyncClient(timeout=timeout, follow_redirects=True) as http:
        r = await http.get(url)
    return f"HTTP {r.status_code}\n{r.headers.get('content-type', '')}\n\n{r.text}"
