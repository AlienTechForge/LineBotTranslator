"""Runtime settings, read from the environment on every call (so tests can monkeypatch)."""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

DEFAULT_CLAUDE_MODEL = "claude-opus-5-5"
DEFAULT_IMAGE_MODEL = "google/gemini-2.5-flash-image"
MAX_UPLOAD_BYTES = 20 * 1024 * 1024
REQUEST_TIMEOUT_S = 600.0  # 10 minutes everywhere (SDK, httpx2, uvicorn keep-alive)

# language code -> English name used in prompts
LANGUAGES: dict[str, str] = {
    "en": "English",
    "zh-TW": "Traditional Chinese (Taiwan)",
    "zh-CN": "Simplified Chinese",
    "ja": "Japanese",
    "ko": "Korean",
    "vi": "Vietnamese",
}


@dataclass(frozen=True)
class Settings:
    claude_model: str
    openrouter_image_model: str
    openrouter_image_api: str  # auto | chat | images
    lab_token: str
    mock: bool
    data_dir: Path
    port: int


def _env(name: str, default: str = "") -> str:
    value = os.environ.get(name)
    if value is None:
        return default
    value = value.strip()
    return value if value else default


def openrouter_api_key() -> str:
    """OPENROUTER_API_KEY, or the name the production bot uses (OPEN_ROUTE_API_KEY)."""
    return _env("OPENROUTER_API_KEY") or _env("OPEN_ROUTE_API_KEY")


def openrouter_referer() -> str:
    return _env("OPENROUTER_HTTP_REFERER") or _env("OPEN_ROUTE_HTTP_REFERER")


def get_settings() -> Settings:
    api = _env("OPENROUTER_IMAGE_API", "auto").lower()
    if api not in {"auto", "chat", "images"}:
        api = "auto"
    try:
        port = int(_env("LAB_PORT", "4041"))
    except ValueError:
        port = 4041
    return Settings(
        claude_model=_env("CLAUDE_MODEL", DEFAULT_CLAUDE_MODEL),
        openrouter_image_model=_env("OPENROUTER_IMAGE_MODEL", DEFAULT_IMAGE_MODEL),
        openrouter_image_api=api,
        lab_token=_env("LAB_TOKEN"),
        mock=_env("LAB_MOCK") == "1",
        data_dir=Path(_env("LAB_DATA_DIR", "./data")),
        port=port,
    )
