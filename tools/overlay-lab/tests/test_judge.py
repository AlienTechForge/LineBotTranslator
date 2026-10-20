"""The semantic judge: the exact request, the validated answer, cost, and never failing the attempt."""

import json
import logging

import anthropic
import httpx2
import pytest
from scenario import ocr

from app import judge
from app.models import ModeError

SOURCE_TEXT = "TEL 77446716\n営業時間\nメニュー\n\n  ご飯   定食 "
SOURCE = ocr(("TEL", "en"), ("77446716", "en"), ("営業時間", "ja"), ("メニュー", "ja"), text=SOURCE_TEXT)
OUTPUT_TEXT = "TEL 77446716\nBanero Salaino\nAz-infe Rilchkan"
OUTPUT = ocr(("TEL", "en"), ("77446716", "en"), ("Banero", "en"), text=OUTPUT_TEXT)

ANSWER = {
    "reference_translation": ["TEL 77446716", "Business hours", "Menu", "Rice set meal"],
    "coverage": 0.42,
    "garbled_segments": [{"output_text": "Banero Salaino", "source_text": "営業時間"}],
    "untranslated_segments": ["ご飯"],
    "mistranslated_segments": [{"output_text": "Az-infe", "source_text": "メニュー", "problem": "not a word"}],
    "altered_numbers": [{"source": "77446716", "output": "77446716 "}],
    "summary": "A column of invented words replaced the menu.",
}


def message_json(text, *, in_tok=2500, out_tok=700, model="claude-sonnet-5-5", stop_reason="end_turn"):
    return {
        "id": "msg_judge",
        "type": "message",
        "role": "assistant",
        "model": model,
        "content": [{"type": "text", "text": text}],
        "stop_reason": stop_reason,
        "stop_sequence": None,
        "usage": {"input_tokens": in_tok, "output_tokens": out_tok},
    }


class Seen(list):
    """The requests the mocked transport received: (path, headers, body); `kwargs` = the client constructions."""

    def __init__(self):
        super().__init__()
        self.kwargs: list = []


@pytest.fixture
def sdk(monkeypatch):
    """The real anthropic.Anthropic over a MockTransport: nothing leaves the box. Returns (seen, state)."""
    monkeypatch.setenv("ANTHROPIC_API_KEY", "test-anthropic-key-0000000000")
    seen = Seen()
    state = {"handler": lambda req, body: httpx2.Response(200, json=message_json(json.dumps(ANSWER)))}
    real = anthropic.Anthropic

    def transport(request: httpx2.Request) -> httpx2.Response:
        body = json.loads(request.content)
        seen.append((request.url.path, dict(request.headers), body))
        return state["handler"](request, body)

    def factory(**kw):
        seen.kwargs.append(kw)
        return real(
            api_key="test-anthropic-key-0000000000",
            max_retries=0,
            http_client=anthropic.DefaultHttpxClient(transport=httpx2.MockTransport(transport)),
        )

    monkeypatch.setattr(anthropic, "Anthropic", factory)
    return seen, state


def error_response(status, message="boom"):
    return httpx2.Response(status, json={"type": "error", "error": {"type": "api_error", "message": message}})


# ---------------------------------------------------------------- the request


def test_the_request_on_the_wire_matches_the_brief(sdk):
    seen, _ = sdk
    record = judge.judge_output(SOURCE, OUTPUT, "zh-TW")

    path, headers, body = seen[0]
    assert path == "/v1/messages" and len(seen) == 1  # one Claude call per attempt
    assert body["model"] == "claude-sonnet-5-5"
    assert body["max_tokens"] == 8000
    assert "thinking" not in body
    assert body["output_config"]["effort"] == "medium"
    fmt = body["output_config"]["format"]
    assert fmt["type"] == "json_schema" and fmt["schema"] == json.loads(json.dumps(judge.JUDGE_SCHEMA))
    assert body["system"] == judge.SYSTEM_PROMPT
    # server-side fallbacks, like Mode A
    assert body["fallbacks"] == "default" and "server-side-fallback-2026-07-01" in headers["anthropic-beta"]

    (message,) = body["messages"]
    assert message["role"] == "user" and [p["type"] for p in message["content"]] == ["text"]
    text = message["content"][0]["text"]
    assert text.startswith("Target language: Traditional Chinese (Taiwan) (zh-TW)\n")
    # the source and the output OCR text, one numbered row per line, blank lines dropped, spaces folded
    assert "SOURCE lines (4), the original image:\nS1: TEL 77446716\nS2: 営業時間\nS3: メニュー\nS4: ご飯 定食\n" in text
    assert "OUTPUT lines (3), the model's redrawn image:\nO1: TEL 77446716\nO2: Banero Salaino\nO3: Az-infe Rilchkan" in text
    assert text.index("SOURCE lines") < text.index("OUTPUT lines")
    assert "image" not in [p["type"] for p in message["content"]]  # text only, never the picture

    assert record["status"] == "ok" and record["model"] == "claude-sonnet-5-5"
    assert record["fallback_form"] == "default" and record["fallback_attempts"] == []
    assert (record["source_lines"], record["output_lines"], record["truncated"]) == (4, 3, False)


def test_the_answer_is_validated_and_every_field_comes_back(sdk):
    record = judge.judge_output(SOURCE, OUTPUT, "en")
    assert record["coverage"] == 0.42
    assert record["reference_translation"] == ANSWER["reference_translation"]
    assert record["garbled_segments"] == [{"output_text": "Banero Salaino", "source_text": "営業時間"}]
    assert record["untranslated_segments"] == ["ご飯"]
    assert record["mistranslated_segments"] == [{"output_text": "Az-infe", "source_text": "メニュー", "problem": "not a word"}]
    assert record["altered_numbers"] == [{"source": "77446716", "output": "77446716 "}]
    assert record["summary"] == ANSWER["summary"]
    assert record["error"] is None and isinstance(record["elapsed_ms"], int)


def test_tokens_and_cost_use_the_price_table(sdk):
    record = judge.judge_output(SOURCE, OUTPUT, "en")
    assert (record["input_tokens"], record["output_tokens"]) == (2500, 700)
    assert record["cost_usd"] == pytest.approx(2500 * 2.0 / 1e6 + 700 * 10.0 / 1e6)  # claude-sonnet-5-5: 2 / 10 USD per Mtok
    assert record["served_model"] == "claude-sonnet-5-5"


def test_the_model_comes_from_claude_judge_model_and_an_unknown_one_has_no_price(sdk, monkeypatch):
    seen, state = sdk
    monkeypatch.setenv("CLAUDE_JUDGE_MODEL", "claude-haiku-5-5")
    state["handler"] = lambda req, body: httpx2.Response(200, json=message_json(json.dumps(ANSWER), model="claude-haiku-5-5"))
    record = judge.judge_output(SOURCE, OUTPUT, "en")
    assert seen[0][2]["model"] == "claude-haiku-5-5" and record["model"] == "claude-haiku-5-5"
    assert record["cost_usd"] == pytest.approx(2500 * 0.10 / 1e6 + 700 * 0.50 / 1e6)

    monkeypatch.setenv("CLAUDE_JUDGE_MODEL", "claude-next-9")
    state["handler"] = lambda req, body: httpx2.Response(200, json=message_json(json.dumps(ANSWER), model="claude-next-9"))
    record = judge.judge_output(SOURCE, OUTPUT, "en")
    assert record["status"] == "ok" and record["cost_usd"] is None and record["input_tokens"] == 2500


def test_the_judge_steps_down_the_fallback_ladder_like_mode_a(sdk):
    seen, state = sdk

    def handler(req, body):
        if "anthropic-beta" in req.headers:
            return httpx2.Response(400, json={"type": "error", "error": {"type": "invalid_request_error", "message": "fallbacks: no"}})
        return httpx2.Response(200, json=message_json(json.dumps(ANSWER)))

    state["handler"] = handler
    record = judge.judge_output(SOURCE, OUTPUT, "en")
    assert record["status"] == "ok" and record["fallback_form"] == "none" and len(record["fallback_attempts"]) == 2
    assert len(seen) == 3 and "fallbacks" not in seen[2][2] and seen[2][2]["output_config"]["effort"] == "medium"


def test_the_judge_uses_the_mode_a_client_settings(sdk):
    seen, _ = sdk
    judge.judge_output(SOURCE, OUTPUT, "en")
    assert seen.kwargs[0]["max_retries"] == 2 and seen.kwargs[0]["timeout"] == judge.TIMEOUT_S
    assert judge.TIMEOUT_S <= 600


def test_the_schema_is_strict_enough_for_structured_outputs():
    def walk(node):
        if isinstance(node, dict):
            if node.get("type") == "object":
                assert node["additionalProperties"] is False
                assert sorted(node["required"]) == sorted(node["properties"])
            for child in node.values():
                walk(child)
        elif isinstance(node, list):
            for child in node:
                walk(child)

    walk(judge.JUDGE_SCHEMA)
    assert set(judge.JUDGE_SCHEMA["required"]) == {
        "reference_translation", "coverage", "garbled_segments", "untranslated_segments",
        "mistranslated_segments", "altered_numbers", "summary",
    }  # fmt: skip


# ---------------------------------------------------------------- failures never fail the attempt


def refusal(req, body):
    return httpx2.Response(200, json=message_json("", stop_reason="refusal"))


@pytest.mark.parametrize(
    "handler, expected",
    [
        (lambda req, body: error_response(500, "upstream down"), "500"),
        (lambda req, body: error_response(401, "bad key"), "401"),
        (lambda req, body: error_response(429, "rate limited"), "429"),
        (refusal, "refused"),
        (lambda req, body: httpx2.Response(200, json=message_json("{", stop_reason="max_tokens")), "max_tokens"),
        (lambda req, body: httpx2.Response(200, json=message_json("Sure, here you go")), "not valid JSON"),
        (lambda req, body: httpx2.Response(200, json=message_json(json.dumps({"summary": "no coverage"}))), "schema"),
        (lambda req, body: httpx2.Response(200, json=message_json(json.dumps({**ANSWER, "coverage": "high"}))), "schema"),
        (lambda req, body: httpx2.Response(200, json=message_json(json.dumps({**ANSWER, "garbled_segments": "x"}))), "schema"),
    ],
)
def test_a_judge_failure_is_unavailable_and_does_not_raise(sdk, handler, expected):
    _, state = sdk
    state["handler"] = handler
    record = judge.judge_output(SOURCE, OUTPUT, "en")
    assert record["status"] == "unavailable" and expected in record["error"]
    assert record["model"] == "claude-sonnet-5-5" and "coverage" not in record


def test_a_connection_error_and_a_missing_key_are_unavailable_too(sdk, monkeypatch):
    _, state = sdk

    def dead(req, body):
        raise httpx2.ConnectError("no route to host", request=req)

    state["handler"] = dead
    record = judge.judge_output(SOURCE, OUTPUT, "en")
    assert record["status"] == "unavailable" and "connection" in record["error"].lower()

    monkeypatch.delenv("ANTHROPIC_API_KEY")
    state["handler"] = lambda req, body: pytest.fail("no key: the API must not be called")
    record = judge.judge_output(SOURCE, OUTPUT, "en")
    assert record["status"] == "unavailable" and "ANTHROPIC_API_KEY" in record["error"]


def test_an_unexpected_exception_inside_the_judge_is_contained(sdk, monkeypatch):
    def boom(*a, **k):
        raise KeyError("something unexpected")

    monkeypatch.setattr(judge, "build_user_text", boom)
    record = judge.judge_output(SOURCE, OUTPUT, "en")
    assert record["status"] == "unavailable" and "KeyError" in record["error"]


def test_errors_never_carry_the_api_key(sdk):
    _, state = sdk
    state["handler"] = lambda req, body: error_response(500, "failed for key test-anthropic-key-0000000000")
    record = judge.judge_output(SOURCE, OUTPUT, "en")
    assert "test-anthropic-key-0000000000" not in json.dumps(record) and "***" in record["error"]


def test_coverage_is_clamped_into_zero_to_one(sdk):
    _, state = sdk
    for raw, expected in ((1.7, 1.0), (-0.3, 0.0), (0.5, 0.5), (1, 1.0)):
        state["handler"] = lambda req, body, raw=raw: httpx2.Response(200, json=message_json(json.dumps({**ANSWER, "coverage": raw})))
        assert judge.judge_output(SOURCE, OUTPUT, "en")["coverage"] == expected


def test_extra_fields_in_the_answer_are_ignored_and_optional_lists_default_to_empty(sdk):
    _, state = sdk
    minimal = {"coverage": 0.95, "summary": "fine", "extra": 1}
    state["handler"] = lambda req, body: httpx2.Response(200, json=message_json(json.dumps(minimal)))
    record = judge.judge_output(SOURCE, OUTPUT, "en")
    assert record["status"] == "ok" and record["garbled_segments"] == [] and record["reference_translation"] == []


# ---------------------------------------------------------------- the input


def test_ocr_lines_use_the_full_text_lines_else_the_paragraphs():
    assert judge.ocr_lines(SOURCE) == ["TEL 77446716", "営業時間", "メニュー", "ご飯 定食"]
    no_text = ocr(("Hello", "en"), ("World", "en"), text="")
    assert judge.ocr_lines(no_text) == ["Hello World"]  # one paragraph (all stand-in words share para 0)
    assert judge.ocr_lines(ocr(text="")) == []


def test_long_pages_are_cut_and_the_record_says_so(sdk):
    seen, _ = sdk
    big = ocr(("x", "en"), text="\n".join(f"line {i}" for i in range(judge.MAX_LINES + 50)))
    record = judge.judge_output(big, big, "en")
    assert record["status"] == "ok" and record["truncated"] is True
    assert (record["source_lines"], record["output_lines"]) == (judge.MAX_LINES, judge.MAX_LINES)
    text = seen[0][2]["messages"][0]["content"][0]["text"]
    assert f"S{judge.MAX_LINES}:" in text and f"S{judge.MAX_LINES + 1}:" not in text
    wide = ocr(("x", "en"), text="y" * (judge.MAX_LINE_CHARS + 100))
    assert judge.judge_output(wide, wide, "en")["truncated"] is True


def test_an_output_without_text_is_still_judged(sdk):
    seen, _ = sdk
    judge.judge_output(SOURCE, ocr(text=""), "en")
    text = seen[0][2]["messages"][0]["content"][0]["text"]
    assert "OUTPUT lines (0)" in text and "(no text was recognised in the output image)" in text


def test_a_source_without_text_is_skipped_without_calling_claude(sdk):
    seen, _ = sdk
    record = judge.judge_output(ocr(text=""), OUTPUT, "en")
    assert record["status"] == "skipped" and "nothing to judge" in record["note"] and seen == []


# ---------------------------------------------------------------- mock mode and logging


def test_mock_mode_has_a_canned_answer_and_is_fully_offline(monkeypatch):
    monkeypatch.setenv("LAB_MOCK", "1")
    monkeypatch.setattr(anthropic, "Anthropic", lambda **kw: pytest.fail("mock mode must not build an SDK client"))
    record = judge.judge_output(SOURCE, OUTPUT, "en")
    assert record["status"] == "ok" and record["fallback_form"] == "mock" and record["coverage"] == 1.0
    assert record["garbled_segments"] == [] and record["untranslated_segments"] == []
    assert record["reference_translation"] == [f"(mock) {line}" for line in judge.ocr_lines(SOURCE)]
    assert (record["input_tokens"], record["output_tokens"]) == (1800, 420)
    assert record["cost_usd"] == pytest.approx(1800 * 2.0 / 1e6 + 420 * 10.0 / 1e6)
    assert judge.judge_output(ocr(text=""), OUTPUT, "en")["status"] == "skipped"


def test_nothing_about_the_ocr_text_is_logged_at_info(sdk, caplog):
    with caplog.at_level(logging.INFO):
        judge.judge_output(SOURCE, OUTPUT, "en")
    logged = " ".join(r.getMessage() for r in caplog.records)
    assert "stage=judge" in logged and "coverage=0.42" in logged
    for secret in ("77446716", "Banero", "営業時間", "Salaino", "test-anthropic-key"):
        assert secret not in logged


def readme_text():
    from pathlib import Path

    readme = Path(__file__).resolve().parents[1] / "README.md"
    if not readme.exists():  # the container image does not carry the README
        pytest.skip("README.md not available")
    return readme.read_text(encoding="utf-8")


def test_readme_shows_the_judge_prompt_that_the_code_uses():
    text = readme_text()
    assert judge.SYSTEM_PROMPT in text
    assert "CLAUDE_JUDGE_MODEL" in text and "claude-sonnet-5-5" in text
    for field in judge.JUDGE_SCHEMA["required"]:
        assert f"`{field}`" in text, field


def test_readme_lists_every_verdict_in_the_precedence_order():
    from app import verify

    text = readme_text()
    for rank, verdict in enumerate(verify.VERDICTS_BEST_TO_WORST, start=1):
        assert f"| {rank} | `{verdict}` |" in text, verdict
    assert "`unverified`" in text


def test_env_example_and_compose_know_the_judge_model():
    from pathlib import Path

    root = Path(__file__).resolve().parents[1]
    if not (root / ".env.example").exists():
        pytest.skip("deployment files not available")
    assert "CLAUDE_JUDGE_MODEL=claude-sonnet-5-5" in (root / ".env.example").read_text(encoding="utf-8")
    assert "CLAUDE_JUDGE_MODEL" in (root / "docker-compose.yml").read_text(encoding="utf-8")


def test_parse_answer_rejects_what_the_schema_does_not_allow():
    with pytest.raises(ModeError, match="not valid JSON"):
        judge.parse_answer("nope")
    with pytest.raises(ModeError, match="schema"):
        judge.parse_answer(json.dumps({"reference_translation": []}))
    assert judge.parse_answer(json.dumps(ANSWER)).coverage == 0.42
