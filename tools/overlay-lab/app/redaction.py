"""Keep credentials out of anything that reaches the UI, result.json or the published manifest."""

from __future__ import annotations

import os

SECRET_ENV = (
    "ANTHROPIC_API_KEY",
    "ANTHROPIC_AUTH_TOKEN",
    "OPENROUTER_API_KEY",
    "OPEN_ROUTE_API_KEY",
    "LAB_TOKEN",
    "MINIO_ACCESS_KEY",
    "MINIO_SECRET_KEY",
)


def redact(message: str) -> str:
    """Never let a credential travel into the UI or the result file."""
    for name in SECRET_ENV:
        value = os.environ.get(name, "")
        if len(value) >= 8:
            message = message.replace(value, "***")
    return message


def describe_error(exc: BaseException) -> str:
    from .models import ModeError

    text = str(exc) if isinstance(exc, ModeError) else f"{type(exc).__name__}: {exc}"
    return redact(text)[:2000]
