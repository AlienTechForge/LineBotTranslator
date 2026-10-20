"""Runtime settings, read from the environment on every call (so tests can monkeypatch)."""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

DEFAULT_CLAUDE_MODEL = "claude-opus-5-5"
DEFAULT_JUDGE_MODEL = "claude-sonnet-5-5"  # the semantic judge of Mode B (app/judge.py)
DEFAULT_IMAGE_MODEL = "google/gemini-2.5-flash-image"
MAX_UPLOAD_BYTES = 20 * 1024 * 1024
MAX_IMAGE_MODELS = 4  # Mode B models compared side by side in one job
MAX_RUNS = 3  # independent runs of every Mode B model in one job (batch mode)
MAX_CONCURRENT_GENERATIONS = 4  # image generations in flight at once, whatever models x runs were asked for
# Output-size tiers of the OpenRouter Image API (`resolution`); "" = leave the provider default
OUTPUT_SIZES = ("", "1K", "2K", "4K")
DEFAULT_OUTPUT_SIZE = "2K"  # what the page preselects and the API uses when `output_size` is omitted
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
    claude_judge_model: str = DEFAULT_JUDGE_MODEL


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


@dataclass(frozen=True)
class MinioSettings:
    """Where the finished job is published for a reviewer (all optional; see publish.py)."""

    endpoint: str  # upload endpoint, as configured (may carry a scheme)
    public_endpoint: str  # endpoint used to sign the links the reviewer opens ("" = same as endpoint)
    access_key: str
    secret_key: str
    bucket: str
    prefix: str
    region: str
    expiry_hours: int  # clamped to 1..168 (SigV4 presigned URLs live at most 7 days)

    @property
    def configured(self) -> bool:
        return bool(self.endpoint and self.access_key and self.secret_key)

    def missing(self) -> list[str]:
        """Names of the variables that keep publishing from running (never their values)."""
        names = []
        if not self.endpoint:
            names.append("MINIO_ENDPOINT (or MINIO_PUBLIC_ENDPOINT)")
        if not self.access_key:
            names.append("MINIO_ACCESS_KEY")
        if not self.secret_key:
            names.append("MINIO_SECRET_KEY")
        return names


def get_minio_settings() -> MinioSettings:
    """Same variable names as the production bot, so they can be copied from its container."""
    public = _env("MINIO_PUBLIC_ENDPOINT")
    try:
        hours = int(_env("MINIO_URL_EXPIRY_HOURS", "168"))
    except ValueError:
        hours = 168
    return MinioSettings(
        endpoint=_env("MINIO_ENDPOINT") or public,
        public_endpoint=public,
        access_key=_env("MINIO_ACCESS_KEY"),
        secret_key=_env("MINIO_SECRET_KEY"),
        bucket=_env("MINIO_BUCKET_NAME", "line-bot"),
        prefix=_env("MINIO_PREFIX", "overlay-lab").strip("/"),
        region=_env("MINIO_REGION", "us-east-1"),
        expiry_hours=min(max(hours, 1), 168),
    )


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
        claude_judge_model=_env("CLAUDE_JUDGE_MODEL", DEFAULT_JUDGE_MODEL),
    )
