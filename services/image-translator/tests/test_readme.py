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
        names |= set(re.findall(r'_(?:env|flag|number)\(\s*"([A-Z][A-Z0-9_]+)"', source))
        names |= set(re.findall(r'os\.environ\.get\(\s*"([A-Z][A-Z0-9_]+)"', source))
    names |= {"LAB_FONT_CJK", "LAB_FONT_LATIN"}  # built from f"LAB_FONT_{kind}" in fonts.py
    assert {"SERVICE_TOKEN", "BIND_HOST", "PORT", "MIN_ROTATED_COLUMN_PX", "ANTHROPIC_MONTHLY_BUDGET_USD"} <= names
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


def test_the_readme_documents_round_6_with_the_constants_of_the_code():
    text = read("README.md")
    for needle in (
        "### 第 6 輪的發現，以及這一輪改了什麼",
        f"`claude_units.MAX_TOKENS` = {claude_units.MAX_TOKENS}",
        f"`COVERAGE_MIN_SHARE` = {claude_units.COVERAGE_MIN_SHARE:.2f}",
        "覆蓋補翻",
        "補翻 N 字",
        "`coverage_pass`",
        "`planner_coverage`",
        f"`RING_PX`（{erase.RING_PX} px）",
        f"`RING_MATCH_MAX`（{erase.RING_MATCH_MAX:.0f}）",
        f"`FEATHER_SIGMA`（{erase.FEATHER_SIGMA}",
        f"`BOX_SPREAD_WITH_RING_MAX` = {erase.BOX_SPREAD_WITH_RING_MAX:.0f}",
        "`erase_reason`",
        "`erase_ring_color`",
        "`erase_ring_spread`",
        "`group_size_broken`",
        "`group_weight`",
        "同組同字級、同粗細",
        "`target_langs`",
        "每張圖各自的目標語言",
        "第 6 輪的變更（與第 5 輪不相容之處）",
        "不是靠程式去讀 `detail` 的文字",
    ):
        assert needle in text, needle
    assert "`group`" in text and "- group: a short id" in text  # the field and its line of the planner prompt


def test_the_readme_and_env_example_say_which_planner_model_is_the_default_and_which_the_owner_runs():
    for name in ("README.md", ".env.example"):
        text = read(name)
        assert "claude-opus-5-5" in text and "擁有者目前的" in text and "claude-sonnet-5-5" in text, name
    assert "CLAUDE_MODEL=claude-opus-5-5" in read(".env.example")  # the default itself did not change


def test_the_readme_lists_the_new_checks_of_the_rows_rule_and_the_judge_rule():
    text = read("README.md")
    assert "而且它的 `detail` 不是空的" in text
    assert "**每個陣列只列問題**" in text and "會誤導讀者" in text


# ---------------------------------------------------------------- round 7: the production service


def test_the_readme_documents_the_production_api_with_every_status_tier_and_reason():
    from app import policy

    text = read("README.md")
    for needle in (
        "X-Service-Token", "POST /v1/translations", "GET /v1/translations/{id}", "GET /v1/translations/{id}/image.png",
        "GET /healthz", "schema_version", "`request_id`", "`target_lang`", "`policy`", "`mode_b_model`", "service_token_missing",
        "request_id_in_use", "payload_too_large", "unsupported_media_type", "`text.translated_text`", "`text.recognized_text`",
        "Asia/Taipei", "budget.json", "SEND_MIN_USABILITY", "`image/png`",
    ):
        assert needle in text, needle
    for name in (*policy.STATUSES, *policy.TIERS, *policy.POLICIES, *policy.REASONS):
        assert f"`{name}`" in text, name
    for code in ("400", "401", "403", "404", "409", "413", "415", "503"):
        assert f"| {code} |" in text, code


def test_the_json_example_of_the_readme_has_the_keys_of_a_real_response(monkeypatch, tmp_path):
    import io
    import json

    from fastapi.testclient import TestClient
    from PIL import Image

    from app import v1
    from app.main import create_app

    text = read("README.md")
    block = text.split("```json\n", 1)[1].split("\n```", 1)[0]
    example = json.loads(block)
    monkeypatch.setenv("LAB_MOCK", "1")
    monkeypatch.setenv("LAB_DATA_DIR", str(tmp_path))
    monkeypatch.setenv("SERVICE_TOKEN", "t")
    buf = io.BytesIO()
    Image.new("RGB", (640, 480), (245, 239, 224)).save(buf, "PNG")
    real = TestClient(create_app()).post(
        "/v1/translations", headers={"X-Service-Token": "t"}, files={"image": ("a.png", buf.getvalue(), "image/png")},
        data={"target_lang": "zh-TW", "request_id": "example-0001"},
    ).json()  # fmt: skip
    assert list(example) == list(real)
    for key in ("image", "source", "text", "judge", "costs", "budget", "timings_ms"):
        assert list(example[key]) == list(real[key]), key
    assert example["source"] == real["source"]  # the mock's words are not random: the example shows the real numbers
    assert list(example["attempts"][0]) == list(real["attempts"][0])
    assert example["schema_version"] == v1.SCHEMA_VERSION == 1
    assert {k: example[k] for k in ("status", "tier", "reason", "mode", "verdict", "target_lang", "text")} == {
        k: real[k] for k in ("status", "tier", "reason", "mode", "verdict", "target_lang", "text")
    }


def test_the_readme_lists_every_language_of_the_table_with_its_english_name():
    from app import config

    text = read("README.md")
    for code, name in config.LANGUAGES.items():
        assert f"`{code}` {name}" in text, code
    for needle in ("normalize_lang", "zh-Hant", "fr-FR", "Noto Sans Thai", "右到左", "shaping", "`warnings`"):
        assert needle in text, needle


def test_the_readme_describes_the_four_fixes_the_lab_and_the_mock():
    text = read("README.md")
    for needle in (
        "structure_checks", "`structure_warnings`", "_lift_to_group", "`frame_height_from_group`", "`group_frame_height`",
        "prefer_breaks", "2 個以下的中日韓字", "`min_size`", "`MIN_ROTATED_COLUMN_PX`", "`skipped: column too narrow`",
        "/lab/", "/lab/api/jobs", "`Deprecation: true`", "`BIND_HOST`", "`PORT`", "`LAB_PORT`", "LAB_MOCK=1", "`policy=both`",
        "services/image-translator", "tools/overlay-lab",
    ):
        assert needle in text, needle


def test_the_defaults_of_the_new_variables_agree_in_the_code_the_readme_the_env_example_and_compose():
    from app import config

    expected = {
        "OPENROUTER_MONTHLY_BUDGET_USD": config.DEFAULT_OPENROUTER_MONTHLY_BUDGET_USD,
        "ANTHROPIC_MONTHLY_BUDGET_USD": config.DEFAULT_ANTHROPIC_MONTHLY_BUDGET_USD,
        "SEND_MIN_USABILITY": config.DEFAULT_SEND_MIN_USABILITY,
        "V1_DEADLINE_S": config.DEFAULT_V1_DEADLINE_S,
        "V1_RETENTION_MINUTES": config.DEFAULT_V1_RETENTION_MINUTES,
        "MODE_B_MIN_REMAINING_S": config.DEFAULT_MODE_B_MIN_REMAINING_S,
        "MIN_ROTATED_COLUMN_PX": config.DEFAULT_MIN_ROTATED_COLUMN_PX,
    }
    assert expected["SEND_MIN_USABILITY"] == 0.60 and expected["V1_DEADLINE_S"] == 210 and expected["MODE_B_MIN_REMAINING_S"] == 90
    assert (expected["OPENROUTER_MONTHLY_BUDGET_USD"], expected["ANTHROPIC_MONTHLY_BUDGET_USD"]) == (15, 80)
    assert (expected["V1_RETENTION_MINUTES"], expected["MIN_ROTATED_COLUMN_PX"]) == (15, 16)
    readme, env_example, compose = read("README.md"), read(".env.example"), read("docker-compose.yml")
    for name, default in expected.items():
        row = re.search(rf"\| `{name}` \| `([0-9.]+)` \|", readme)
        assert row and float(row.group(1)) == default, f"README: {name}"
        line = re.search(rf"^{name}=([0-9.]+)$", env_example, re.M)
        assert line and float(line.group(1)) == default, f".env.example: {name}"
        var = re.search(rf'{name}: "\$\{{{name}:-([0-9.]+)\}}"', compose)
        assert var and float(var.group(1)) == default, f"docker-compose.yml: {name}"
    assert re.search(r"^SERVICE_TOKEN=$", env_example, re.M)  # empty by default: /v1 answers 503 until it is set
    assert "# BIND_HOST=127.0.0.1" in env_example and "# PORT=4041" in env_example  # commented: compose must not inherit them


def test_the_docker_files_serve_the_whole_service_from_the_new_directory():
    dockerfile, compose, env = read("Dockerfile"), read("docker-compose.yml"), read(".env.example")
    assert "BIND_HOST=0.0.0.0" in dockerfile and "/healthz" in dockerfile and "X-Lab-Token" not in dockerfile
    assert 'BIND_HOST: "0.0.0.0"' in compose and 'SERVICE_TOKEN: "${SERVICE_TOKEN:-}"' in compose
    assert "cd services/image-translator" in compose and "name: overlay-lab" in compose  # the running lab container is replaced in place
    for name, text in (("Dockerfile", dockerfile), ("docker-compose.yml", compose), (".env.example", env)):
        assert "tools/overlay-lab" not in text, name
    assert "COPY pytest.ini ." in dockerfile and "COPY app ./app" in dockerfile and "COPY tests ./tests" in dockerfile


def test_the_selection_rule_the_tiny_text_rule_and_the_new_source_block_are_documented():
    from app import config, policy

    text = read("README.md")
    for needle in (
        "`small_text`", "`MODE_A_MIN_GLYPH_PX`", "`MODE_A_MIN_REMAINING_S`", "`source.median_glyph_px`", "`source`：",
        "先看哪一張送得出去", "`verify.attempt_key`", "`policy._is_fragment`", "`attempts=", "`glyph_px=`", "`stage=policy",
        "Mode A skipped: the source text is too small", "Mode A ran only after Mode B",
    ):
        assert needle in text, needle
    assert "small_text" in policy.REASONS and config.MODE_A_MIN_REMAINING_S == 120.0 and "（常數 120 秒" in text
    row = re.search(r"\| `MODE_A_MIN_GLYPH_PX` \| `([0-9.]+)` \|", text)
    line = re.search(r"^MODE_A_MIN_GLYPH_PX=([0-9.]+)$", read(".env.example"), re.M)
    assert row and line and float(row.group(1)) == float(line.group(1)) == config.DEFAULT_MODE_A_MIN_GLYPH_PX == 12.0
    # every (status, tier, reason) the policy can produce for the small-text path has its own row
    for reason in ("below_threshold", "unverified", "b_failed"):
        assert re.search(rf"\| `error` \| `null` \| `{reason}` \|", text), reason
    assert re.search(r"\| `image` \| `clean` 或 `warned` \| `small_text` \|", text)
