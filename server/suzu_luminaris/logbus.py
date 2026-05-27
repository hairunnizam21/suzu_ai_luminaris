"""In-process log fan-out so the panel's SSE viewer can subscribe.

Also writes every event to settings.log_file so we have a durable trail.
"""

from __future__ import annotations

import asyncio
import json
import time
from pathlib import Path

from .schemas import LogEvent


class LogBus:
    def __init__(self, log_file: Path):
        self._subs: list[asyncio.Queue[LogEvent]] = []
        self._lock = asyncio.Lock()
        self._log_file = log_file
        self._log_file.parent.mkdir(parents=True, exist_ok=True)

    async def emit(self, level: str, source: str, message: str) -> None:
        ev = LogEvent(ts=time.time(), level=level, source=source, message=message)
        line = json.dumps(ev.model_dump()) + "\n"
        try:
            with self._log_file.open("a", encoding="utf-8") as fh:
                fh.write(line)
        except OSError:
            pass

        async with self._lock:
            dead: list[asyncio.Queue[LogEvent]] = []
            for q in self._subs:
                try:
                    q.put_nowait(ev)
                except asyncio.QueueFull:
                    dead.append(q)
            for q in dead:
                self._subs.remove(q)

    def info(self, source: str, message: str) -> asyncio.Task[None]:
        return asyncio.create_task(self.emit("INFO", source, message))

    def warn(self, source: str, message: str) -> asyncio.Task[None]:
        return asyncio.create_task(self.emit("WARN", source, message))

    def error(self, source: str, message: str) -> asyncio.Task[None]:
        return asyncio.create_task(self.emit("ERROR", source, message))

    async def subscribe(self) -> asyncio.Queue[LogEvent]:
        q: asyncio.Queue[LogEvent] = asyncio.Queue(maxsize=2048)
        async with self._lock:
            self._subs.append(q)
        return q

    async def unsubscribe(self, q: asyncio.Queue[LogEvent]) -> None:
        async with self._lock:
            if q in self._subs:
                self._subs.remove(q)
