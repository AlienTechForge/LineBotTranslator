"""Monthly spend of the two paid providers, kept in `<LAB_DATA_DIR>/budget.json`.

    {"month": "2026-10", "openrouter_usd": 3.21, "anthropic_usd": 12.5, "requests": 87}

The month is the calendar month in Asia/Taipei (a fixed UTC+8: Taipei has had no daylight saving
since 1979, and a fixed offset needs no time zone database in a slim image). The first read or write in
a new month starts from zero. Every attempt adds what it cost (policy.Ledger), the limits come from
`OPENROUTER_MONTHLY_BUDGET_USD` (default 15) and `ANTHROPIC_MONTHLY_BUDGET_USD` (default 80), and 0 means
unlimited.

The file is replaced atomically (a temporary file in the same directory, then `os.replace`) under a lock
shared by every thread of the process, so a reader never sees half a file. The limit is a soft one: a
request that started while there was room finishes even when the room runs out in the middle, and two
requests that start together can both pass the check.

Nothing here logs an amount per request at INFO and nothing sees an image or a text.
"""

from __future__ import annotations

import json
import logging
import math
import os
import threading
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

from .config import get_settings

log = logging.getLogger("lab.budget")

TAIPEI = timezone(timedelta(hours=8))
FILE_NAME = "budget.json"
_lock = threading.RLock()


def now() -> datetime:
    """The current moment (tests replace this to move the clock)."""
    return datetime.now(TAIPEI)


def current_month() -> str:
    return now().astimezone(TAIPEI).strftime("%Y-%m")


def path() -> Path:
    return get_settings().data_dir / FILE_NAME


def _fresh(month: str) -> dict:
    return {"month": month, "openrouter_usd": 0.0, "anthropic_usd": 0.0, "requests": 0}


def _valid(state: object) -> bool:
    if not isinstance(state, dict) or not isinstance(state.get("month"), str):
        return False
    for key in ("openrouter_usd", "anthropic_usd"):
        value = state.get(key)
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0:
            return False
    requests = state.get("requests")
    return isinstance(requests, int) and not isinstance(requests, bool) and requests >= 0


def _load(file: Path, month: str) -> dict:
    """The state of `month`: what the file holds for it, else a fresh one (a new month, no file, a damaged file)."""
    try:
        state = json.loads(file.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return _fresh(month)
    except (OSError, ValueError):
        state = None
    if not _valid(state):
        _quarantine(file)
        return _fresh(month)
    if state["month"] != month:
        return _fresh(month)
    return {
        "month": month,
        "openrouter_usd": float(state["openrouter_usd"]),
        "anthropic_usd": float(state["anthropic_usd"]),
        "requests": int(state["requests"]),
    }


def _quarantine(file: Path) -> None:
    """Keep a damaged file under another name instead of silently overwriting the evidence."""
    target = file.with_name(f"{file.name}.damaged-{int(time.time())}")
    try:
        os.replace(file, target)
        log.warning("stage=budget the budget file was not readable and was moved aside; counting from zero")
    except OSError:
        log.warning("stage=budget the budget file is not readable and could not be moved aside")


def _write(file: Path, state: dict) -> None:
    """Replace the file atomically: write a temporary file next to it, flush it to disk, rename it over."""
    file.parent.mkdir(parents=True, exist_ok=True)
    tmp = file.with_name(f".{file.name}.{os.getpid()}.{threading.get_ident()}.tmp")
    try:
        with open(tmp, "w", encoding="utf-8") as handle:
            json.dump(state, handle)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(tmp, file)
    finally:
        try:
            tmp.unlink()
        except FileNotFoundError:
            pass


def state() -> dict:
    """The stored numbers of this month ({month, openrouter_usd, anthropic_usd, requests})."""
    with _lock:
        return _load(path(), current_month())


def limits() -> tuple[float, float]:
    """(OpenRouter, Anthropic) monthly limits in USD; 0 means unlimited."""
    settings = get_settings()
    return settings.openrouter_monthly_budget_usd, settings.anthropic_monthly_budget_usd


def snapshot() -> dict:
    """The numbers every response and /healthz show."""
    current = state()
    openrouter_limit, anthropic_limit = limits()
    return {
        "month": current["month"],
        "openrouter_spent_usd": round(current["openrouter_usd"], 6),
        "openrouter_budget_usd": openrouter_limit,
        "anthropic_spent_usd": round(current["anthropic_usd"], 6),
        "anthropic_budget_usd": anthropic_limit,
    }


def add(openrouter_usd: float = 0.0, anthropic_usd: float = 0.0, requests: int = 0) -> dict:
    """Add costs (and requests) to this month and write the file; returns the new state.

    A negative, non-finite or non-numeric amount counts as 0. The file is written even when everything
    added is 0, so that it exists as soon as the first request has run (mock mode included).
    """

    def clean(value: object) -> float:
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0:
            return 0.0
        return float(value)

    with _lock:
        file = path()
        current = _load(file, current_month())
        current["openrouter_usd"] = round(current["openrouter_usd"] + clean(openrouter_usd), 6)
        current["anthropic_usd"] = round(current["anthropic_usd"] + clean(anthropic_usd), 6)
        current["requests"] += max(0, int(requests))
        try:
            _write(file, current)
        except OSError:
            log.warning("stage=budget could not write %s", FILE_NAME)  # the request goes on; the numbers are lost
        return current


def allows_openrouter() -> bool:
    """True while this month's OpenRouter spend is below the limit (always when the limit is 0)."""
    limit = limits()[0]
    return limit == 0 or state()["openrouter_usd"] < limit


def allows_anthropic() -> bool:
    """True while this month's Anthropic spend is below the limit (always when the limit is 0)."""
    limit = limits()[1]
    return limit == 0 or state()["anthropic_usd"] < limit
