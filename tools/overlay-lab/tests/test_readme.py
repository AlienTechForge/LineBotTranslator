"""README.md, .env.example and docker-compose.yml say what the code does (skipped where the files are not in the image)."""

import re
from pathlib import Path

import pytest

from app import claude_units, erase, judge, layout, openrouter, verify

ROOT = Path(__file__).resolve().parents[1]


def read(name: str) -> str:
    path = ROOT / name
    if not path.exists():  # the container image carries neither the README nor the deployment files
        pytest.skip(f"{name} not available")
    return path.read_text(encoding="utf-8")


def test_the_readme_shows_the_planner_prompt_that_the_code_uses():
    text = read("README.md")
    assert claude_units.SYSTEM_PROMPT in text
    for field in ("short", "align", "weight", "importance"):
        assert f"`{field}`" in text, field


def test_the_readme_shows_both_prompts_and_every_retry_line_of_the_code():
    text = read("README.md")
    assert judge.SYSTEM_PROMPT in text and openrouter.PROMPT_TEMPLATE in text and openrouter.RETRY_TEMPLATE in text
    for line in (
        openrouter.RETRY_NUMBERS_LINE,
        openrouter.RETRY_ROWS_LINE,
        openrouter.RETRY_GARBLED_LINE,
        openrouter.RETRY_UNTRANSLATED_LINE,
    ):
        assert line in text


def test_the_readme_has_the_verdict_table_in_the_order_of_the_checks():
    text = read("README.md")
    positions = [text.index(f"| `{name}` |") for name in verify.CHECKS]
    assert positions == sorted(positions) and len(set(positions)) == 5
    assert f"`USABILITY_MIN`（常數，預設 {verify.USABILITY_MIN:.2f}" in text
    for word in ("duplicated_row", "missing_row", "misaligned_marks", "unverified", "`pass`"):
        assert word in text, word


def test_the_readme_states_the_constants_of_the_mode_a_upgrade():
    text = read("README.md")
    for needle in (
        f"`MIN_FIT_RATIO`（{layout.MIN_FIT_RATIO:.2f}）",
        f"`MAX_GROW`（{layout.MAX_GROW:.1f}）",
        f"`FLAT_STD_MAX`（{erase.FLAT_STD_MAX:.0f}",
        f"`INK_DISTANCE`（{erase.INK_DISTANCE:.0f}",
        f"`GUARD_TOLERANCE`（{erase.GUARD_TOLERANCE:.0f}",
        f"`MAX_INK_SHARE`（{erase.MAX_INK_SHARE:.0%}）",
        "`erase_method`",
        "`text_used`",
        "`skipped: too small`",
        "`MODE_A_RETRY`",
        "Noto Sans",
        "fonts-noto-core",
        "轉 90 度",
        "孤字",
    ):
        assert needle in text, needle


def test_the_readme_documents_the_batch_the_cost_split_and_the_breaking_changes():
    text = read("README.md")
    for needle in (
        "POST /api/batches",
        "GET /api/batches/<batch-id>",
        "batch-<batch-id>/manifest.json",
        "批次彙總表",
        "Anthropic（額度）",
        "OpenRouter（現金）",
        "schema_version: 3",
        "A_a1.png",
        "A_a2.png",
        "numbers",
        "`digits-changed`",  # named as a breaking change
        "第 5 輪的破壞性變更",
        "約 1.6k tokens",
        "估計，不是實測",
        "OCR 警告",
    ):
        assert needle in text, needle


def test_every_environment_variable_the_app_reads_is_in_the_readme_and_the_env_example():
    names = set()
    for module in ("config.py", "colors.py", "fonts.py", "redaction.py"):
        source = (ROOT / "app" / module).read_text(encoding="utf-8")
        names |= set(re.findall(r'_(?:env|flag)\(\s*"([A-Z][A-Z0-9_]+)"', source))
        names |= set(re.findall(r'os\.environ\.get\(\s*"([A-Z][A-Z0-9_]+)"', source))
    names |= {"LAB_FONT_CJK", "LAB_FONT_LATIN"}  # built from f"LAB_FONT_{kind}" in fonts.py
    readme, env_example = read("README.md"), read(".env.example")
    # ANTHROPIC_AUTH_TOKEN is the SDK's own alias, not a lab setting
    for name in sorted(names - {"ANTHROPIC_AUTH_TOKEN"}):
        assert name in readme, f"{name} is missing from the README"
        assert name in env_example, f"{name} is missing from .env.example"
    assert "MODE_A_RETRY" in names


def test_the_env_example_and_compose_default_to_the_round_5_model_and_retry():
    env, compose = read(".env.example"), read("docker-compose.yml")
    assert "OPENROUTER_IMAGE_MODEL=google/gemini-3-pro-image" in env
    assert "google/gemini-3-pro-image" in compose and "gemini-2.5-flash-image" not in compose + env
    assert 'MODE_A_RETRY: "${MODE_A_RETRY:-1}"' in compose and "MODE_A_RETRY=1" in env
