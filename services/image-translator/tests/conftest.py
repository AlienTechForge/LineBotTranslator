"""pytest fixtures for the image-translator service."""

import pytest

ENV_NAMES = (
    "LAB_MOCK",
    "LAB_TOKEN",
    "LAB_DATA_DIR",
    "ANTHROPIC_API_KEY",
    "ANTHROPIC_AUTH_TOKEN",
    "OPENROUTER_API_KEY",
    "OPEN_ROUTE_API_KEY",
    "OPEN_ROUTE_HTTP_REFERER",
    "OPENROUTER_HTTP_REFERER",
    "LAB_INK_FRACTION",
    "OPENROUTER_IMAGE_API",
    "OPENROUTER_IMAGE_MODEL",
    "CLAUDE_MODEL",
    "CLAUDE_JUDGE_MODEL",
    "MODE_A_RETRY",
    "MINIO_ENDPOINT",
    "MINIO_PUBLIC_ENDPOINT",
    "MINIO_ACCESS_KEY",
    "MINIO_SECRET_KEY",
    "MINIO_BUCKET_NAME",
    "MINIO_PREFIX",
    "MINIO_REGION",
    "MINIO_URL_EXPIRY_HOURS",
    "SERVICE_TOKEN",
    "BIND_HOST",
    "PORT",
    "LAB_PORT",
    "OPENROUTER_MONTHLY_BUDGET_USD",
    "ANTHROPIC_MONTHLY_BUDGET_USD",
    "SEND_MIN_USABILITY",
    "V1_DEADLINE_S",
    "V1_RETENTION_MINUTES",
    "MODE_B_MIN_REMAINING_S",
    "MODE_A_MIN_GLYPH_PX",
    "MIN_ROTATED_COLUMN_PX",
    "CLAUDE_TRANSLATE_MODEL",
    "MODE_C_IMAGE_CONTEXT",
    "MODE_C_CHUNK_UNITS",
    "INPAINT_MODEL",
    "INPAINT_MODEL_PATH",
    "INPAINT_MODEL_URL",
    "INPAINT_MODEL_SHA256",
)


@pytest.fixture(autouse=True)
def clean_env(monkeypatch):
    """Every test starts without any lab / provider variables and sets what it needs."""
    for name in ENV_NAMES:
        monkeypatch.delenv(name, raising=False)
    from app import inpaint

    inpaint.reset()  # no model session and no remembered download failure carry over from another test
