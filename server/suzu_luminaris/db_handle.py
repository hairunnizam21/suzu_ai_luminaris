"""Process-wide handle to the live `Db` instance.

This sidesteps an import cycle: the tools layer needs the database, but
`main.py` (which owns the `Db()` singleton) imports `tools.__init__` via
`agent`. The pattern is: `main.py` calls `register(db)` at startup, and
anything else does `db_handle.get()` to read the live handle.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from .db import Db  # pragma: no cover

_db: "Db | None" = None


def register(db: "Db") -> None:
    global _db
    _db = db


def get() -> "Db":
    if _db is None:
        raise RuntimeError("db not registered yet")
    return _db
