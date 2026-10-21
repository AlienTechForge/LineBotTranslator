"""Runtime settings, read from the environment on every call (so tests can monkeypatch)."""

from __future__ import annotations

import os
import re
from dataclasses import dataclass
from pathlib import Path

DEFAULT_CLAUDE_MODEL = "claude-opus-5-5"
DEFAULT_JUDGE_MODEL = "claude-sonnet-5-5"  # the semantic judge of Mode B (app/judge.py)
DEFAULT_IMAGE_MODEL = "google/gemini-3-pro-image"  # what the page preselects (the owner also tests google/gemini-3.1-flash-image)
MAX_UPLOAD_BYTES = 20 * 1024 * 1024
MAX_IMAGE_MODELS = 4  # Mode B models compared side by side in one job
MAX_RUNS = 3  # independent runs of every Mode B model in one job (batch mode)
MAX_CONCURRENT_GENERATIONS = 4  # image generations in flight at once, whatever models x runs were asked for
MAX_CONCURRENT_CLAUDE = 4  # Claude calls (the Mode A planner and the judge) in flight at once, process wide
MAX_BATCH_FILES = 4  # images in one submission (one job each)
# Output-size tiers of the OpenRouter Image API (`resolution`); "" = leave the provider default
OUTPUT_SIZES = ("", "1K", "2K", "4K")
DEFAULT_OUTPUT_SIZE = "2K"  # what the page preselects and the API uses when `output_size` is omitted
REQUEST_TIMEOUT_S = 600.0  # 10 minutes everywhere (SDK, httpx2, uvicorn keep-alive)

# Production API (app/v1.py, app/policy.py, app/budget.py); every one of them can be set in the environment.
DEFAULT_BIND_HOST = "127.0.0.1"
DEFAULT_PORT = 4041
DEFAULT_SEND_MIN_USABILITY = 0.60  # a picture below this judge usability is not sent: the translated text is
DEFAULT_V1_DEADLINE_S = 210.0  # overall budget of one /v1/translations request, from its start
DEFAULT_V1_RETENTION_MINUTES = 15.0  # how long a record and its picture stay available
DEFAULT_MODE_B_MIN_REMAINING_S = 90.0  # Mode B is not started with less time than this left
DEFAULT_MIN_ROTATED_COLUMN_PX = 16.0  # a vertical column narrower than this is not drawn rotated for a Latin target
DEFAULT_OPENROUTER_MONTHLY_BUDGET_USD = 15.0  # cash; 0 = unlimited
DEFAULT_ANTHROPIC_MONTHLY_BUDGET_USD = 80.0  # 0 = unlimited

# language code -> English name used in prompts
LANGUAGES: dict[str, str] = {
    "en": "English",
    "zh-TW": "Traditional Chinese (Taiwan)",
    "zh-CN": "Simplified Chinese",
    "zh-HK": "Traditional Chinese (Hong Kong)",  # drawn with the zh-TW face
    "ja": "Japanese",
    "ko": "Korean",
    "vi": "Vietnamese",
    "th": "Thai",
    "id": "Indonesian",
    "ms": "Malay",
    "tl": "Filipino (Tagalog)",
    "fr": "French",
    "de": "German",
    "es": "Spanish",
    "it": "Italian",
    "pt": "Portuguese",
    "ru": "Russian",
    "ar": "Arabic",
    "he": "Hebrew",
    "hi": "Hindi",
    "tr": "Turkish",
    "nl": "Dutch",
    "pl": "Polish",
    "uk": "Ukrainian",
}

# Spellings the Java bot (and BCP-47 in general) uses for a code of the table above.
LANGUAGE_ALIASES: dict[str, str] = {
    "zh": "zh-TW",
    "tw": "zh-TW",
    "zh-hant": "zh-TW",
    "zh-hant-tw": "zh-TW",
    "cn": "zh-CN",
    "zh-hans": "zh-CN",
    "zh-hans-cn": "zh-CN",
    "zh-sg": "zh-CN",
    "zh-hans-sg": "zh-CN",
    "zh-hant-hk": "zh-HK",
    "zh-mo": "zh-HK",
    "zh-hant-mo": "zh-HK",
    "fil": "tl",
    "iw": "he",
    "in": "id",
}
LANG_CODE_RE = re.compile(r"^[A-Za-z]{2,8}(?:[-_][A-Za-z0-9]{1,8}){0,3}$")  # what /v1 accepts as a target_lang
MAX_LANG_CODE_CHARS = 35


def normalize_lang(code: str) -> tuple[str, bool]:
    """(code to use, known): a code of LANGUAGES in its own spelling, or the code as given when unknown.

    Matching is case-insensitive, `_` counts as `-`, the aliases above map the usual Chinese tags
    (zh, zh-Hant, zh-Hans ...), and a regional tag of a known language (fr-FR, pt-BR, en-US) is that
    language. Anything else comes back unchanged with known=False: the prompts then use the code itself.
    """
    raw = (code or "").strip().replace("_", "-")
    by_lower = {name.lower(): name for name in LANGUAGES}
    low = raw.lower()
    if low in by_lower:
        return by_lower[low], True
    if low in LANGUAGE_ALIASES:
        return LANGUAGE_ALIASES[low], True
    base = low.split("-")[0]
    if base != "zh" and base in by_lower:
        return by_lower[base], True
    if base in LANGUAGE_ALIASES and base != "zh":
        return LANGUAGE_ALIASES[base], True
    return raw, False


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
    mode_a_retry: bool = True  # MODE_A_RETRY: one more planner call with the judge's findings (the page can override it)
    service_token: str = ""  # SERVICE_TOKEN: the X-Service-Token every /v1 request must carry ("" = /v1 answers 503)
    bind_host: str = DEFAULT_BIND_HOST
    send_min_usability: float = DEFAULT_SEND_MIN_USABILITY
    v1_deadline_s: float = DEFAULT_V1_DEADLINE_S
    v1_retention_minutes: float = DEFAULT_V1_RETENTION_MINUTES
    mode_b_min_remaining_s: float = DEFAULT_MODE_B_MIN_REMAINING_S
    openrouter_monthly_budget_usd: float = DEFAULT_OPENROUTER_MONTHLY_BUDGET_USD
    anthropic_monthly_budget_usd: float = DEFAULT_ANTHROPIC_MONTHLY_BUDGET_USD


def _env(name: str, default: str = "") -> str:
    value = os.environ.get(name)
    if value is None:
        return default
    value = value.strip()
    return value if value else default


def _flag(name: str, default: bool) -> bool:
    """A 0/1/true/false/yes/no/on/off environment flag; anything else (or unset) is `default`."""
    value = _env(name).lower()
    if value in ("1", "true", "yes", "on"):
        return True
    if value in ("0", "false", "no", "off"):
        return False
    return default


def _number(name: str, default: float, low: float | None = None, high: float | None = None) -> float:
    """A float environment variable; unset, not a number, not finite or outside [low, high] is `default`."""
    raw = _env(name)
    if not raw:
        return default
    try:
        value = float(raw)
    except ValueError:
        return default
    if value != value or value in (float("inf"), float("-inf")):
        return default
    if (low is not None and value < low) or (high is not None and value > high):
        return default
    return value


def min_rotated_column_px() -> float:
    """MIN_ROTATED_COLUMN_PX: below this column width a vertical unit is not drawn rotated (Latin targets)."""
    return _number("MIN_ROTATED_COLUMN_PX", DEFAULT_MIN_ROTATED_COLUMN_PX, low=0.0)


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
    raw_port = _env("PORT") or _env("LAB_PORT")  # PORT wins; LAB_PORT is the lab's old name and still honoured
    try:
        port = int(raw_port) if raw_port else DEFAULT_PORT
    except ValueError:
        port = DEFAULT_PORT
    if not 1 <= port <= 65535:
        port = DEFAULT_PORT
    return Settings(
        claude_model=_env("CLAUDE_MODEL", DEFAULT_CLAUDE_MODEL),
        openrouter_image_model=_env("OPENROUTER_IMAGE_MODEL", DEFAULT_IMAGE_MODEL),
        openrouter_image_api=api,
        lab_token=_env("LAB_TOKEN"),
        mock=_env("LAB_MOCK") == "1",
        data_dir=Path(_env("LAB_DATA_DIR", "./data")),
        port=port,
        claude_judge_model=_env("CLAUDE_JUDGE_MODEL", DEFAULT_JUDGE_MODEL),
        mode_a_retry=_flag("MODE_A_RETRY", True),
        service_token=_env("SERVICE_TOKEN"),
        bind_host=_env("BIND_HOST", DEFAULT_BIND_HOST),
        send_min_usability=_number("SEND_MIN_USABILITY", DEFAULT_SEND_MIN_USABILITY, low=0.0, high=1.0),
        v1_deadline_s=_number("V1_DEADLINE_S", DEFAULT_V1_DEADLINE_S, low=1.0),
        v1_retention_minutes=_number("V1_RETENTION_MINUTES", DEFAULT_V1_RETENTION_MINUTES, low=0.01),
        mode_b_min_remaining_s=_number("MODE_B_MIN_REMAINING_S", DEFAULT_MODE_B_MIN_REMAINING_S, low=0.0),
        openrouter_monthly_budget_usd=_number(
            "OPENROUTER_MONTHLY_BUDGET_USD", DEFAULT_OPENROUTER_MONTHLY_BUDGET_USD, low=0.0
        ),
        anthropic_monthly_budget_usd=_number(
            "ANTHROPIC_MONTHLY_BUDGET_USD", DEFAULT_ANTHROPIC_MONTHLY_BUDGET_USD, low=0.0
        ),
    )
