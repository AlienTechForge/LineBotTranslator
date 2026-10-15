"""pytest fixtures for overlay-lab."""

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
)


@pytest.fixture(autouse=True)
def clean_env(monkeypatch):
    """Every test starts without any lab / provider variables and sets what it needs."""
    for name in ENV_NAMES:
        monkeypatch.delenv(name, raising=False)
