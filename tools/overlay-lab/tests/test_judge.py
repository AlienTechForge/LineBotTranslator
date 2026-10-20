"""The vision judge: the exact request (two pictures + OCR aid), the validated answer, cost, never failing the attempt."""

import base64
import io
import json
import logging
from pathlib import Path

import anthropic
import httpx2
import pytest
from PIL import Image
from scenario import ocr

from app import judge
from app.models import ModeError

SOURCE_TEXT = "TEL 77446716\n営業時間\nメニュー\n\n  ご飯   定食 "
SOURCE = ocr(("TEL", "en"), ("77446716", "en"), ("営業時間", "ja"), ("メニュー", "ja"), text=SOURCE_TEXT)


def picture(size, color):
    return Image.new("RGB", size, color)


def png_of(size, color=(40, 90, 160)) -> bytes:
    buf = io.BytesIO()
    picture(size, color).save(buf, "PNG")
    return buf.getvalue()


SOURCE_IMAGE = picture((3000, 2000), (245, 240, 225))  # bigger than the 1568 px cap
OUTPUT_PNG = png_of((800, 600))

ANSWER = {
    "usability": 0.42,
    "coverage": 0.5,
    "legibility_issues": [
        {"where": "price pill of the second row", "output_text": "Manbar Price", "problem": "misspelt label", "major": True},
        {"where": "logo lettering", "output_text": "Sinoe 2024", "problem": "decoration", "major": False},
    ],
    "structure_issues": [{"type": "duplicated_row", "detail": "the scallion beef rice row is drawn twice"}],
    "altered_numbers": [{"source": "77446716", "output": "99446916", "where": "phone number at the bottom"}],
    "untranslated": [{"text": "ご飯", "where": "last column", "major": True}],
    "mistranslated": [{"output_text": "Perm", "source_text": "漂髮", "problem": "bleach is not perm"}],
    "summary": "A row is drawn twice and the phone number changed.",
}


def message_json(text, *, in_tok=3300, out_tok=700, model="claude-sonnet-5-5", stop_reason="end_turn"):
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


def run(lang="zh-TW", source=SOURCE, image=SOURCE_IMAGE, output=OUTPUT_PNG):
    return judge.judge_output(source, image, output, lang)


def decoded(block):
    return Image.open(io.BytesIO(base64.b64decode(block["source"]["data"])))


# ---------------------------------------------------------------- the request


def test_the_request_on_the_wire_matches_the_brief(sdk):
    seen, _ = sdk
    record = run()

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
    assert message["role"] == "user"
    kinds = [p["type"] for p in message["content"]]
    assert kinds == ["text", "image", "text", "image", "text"]  # label, ORIGINAL, label, OUTPUT, text
    label1, first, label2, second, text = message["content"]
    assert label1["text"] == "Image 1 is the ORIGINAL image." and label2["text"] == "Image 2 is the model's OUTPUT image."
    for block in (first, second):  # base64 JPEGs, as Mode A sends its picture
        assert block["source"]["type"] == "base64" and block["source"]["media_type"] == "image/jpeg"
    # both downscaled to a long edge of at most 1568 px; the small one is left alone
    original, output = decoded(first), decoded(second)
    assert original.format == "JPEG" and output.format == "JPEG"
    assert original.size == (1568, 1045) and output.size == (800, 600)
    assert record["images_sent"] == {"source": [1568, 1045], "output": [800, 600]}

    assert text["text"].startswith("Target language: Traditional Chinese (Taiwan) (zh-TW)\n")
    # the OCR lines of the ORIGINAL, one numbered row per line, blank lines dropped, spaces folded: an aid only
    assert "OCR lines of Image 1, the original (4)" in text["text"]
    assert "S1: TEL 77446716\nS2: 営業時間\nS3: メニュー\nS4: ご飯 定食" in text["text"]
    assert "OUTPUT lines" not in text["text"] and "O1:" not in text["text"]  # the output OCR is never sent

    assert record["status"] == "ok" and record["model"] == "claude-sonnet-5-5"
    assert record["fallback_form"] == "default" and record["fallback_attempts"] == []
    assert (record["source_lines"], record["truncated"]) == (4, False)


def test_only_the_two_pictures_and_the_source_ocr_text_reach_anthropic(sdk):
    import inspect

    seen, _ = sdk
    # the judge takes no OCR of the output at all: the source OCR, the two pictures and the language
    assert list(inspect.signature(judge.judge_output).parameters) == ["source_ocr", "source_image", "output_png", "target_lang"]
    run()
    body = json.dumps(seen[0][2])
    assert body.count('"type": "image"') == 2
    texts = [p["text"] for p in seen[0][2]["messages"][0]["content"] if p["type"] == "text"]
    assert len(texts) == 3 and texts[2].count("\nS") == 4  # the three text blocks; the OCR lines are all of the source's


def test_the_prompt_tells_the_model_what_it_sees_and_how_to_judge(sdk):
    prompt = judge.SYSTEM_PROMPT
    for needle in (
        "Image 1 is the ORIGINAL and Image 2 is the OUTPUT",
        "Judge what is actually drawn in Image 2",
        "Be strict about garbled text and changed numbers",
        "Compare tables and lists row by row",
        "Never report something you cannot point to in Image 2",
        "usability",
        "below 0.4 means it is not usable",
        "legibility_issues",
        "duplicated_row",
        "misaligned_marks",
        "altered_numbers",
        "12:30 and 12.30",
        "untranslated",
        "mistranslated",
    ):
        assert needle in prompt, needle


def test_the_answer_is_validated_and_every_field_comes_back(sdk):
    record = run("en")
    assert (record["usability"], record["coverage"]) == (0.42, 0.5)
    assert record["legibility_issues"] == ANSWER["legibility_issues"]
    assert record["structure_issues"] == ANSWER["structure_issues"]
    assert record["altered_numbers"] == ANSWER["altered_numbers"]
    assert record["untranslated"] == ANSWER["untranslated"]
    assert record["mistranslated"] == ANSWER["mistranslated"]
    assert record["summary"] == ANSWER["summary"]
    assert record["error"] is None and isinstance(record["elapsed_ms"], int)


def test_tokens_and_cost_use_the_price_table(sdk):
    record = run("en")
    assert (record["input_tokens"], record["output_tokens"]) == (3300, 700)
    assert record["cost_usd"] == pytest.approx(3300 * 2.0 / 1e6 + 700 * 10.0 / 1e6)  # claude-sonnet-5-5: 2 / 10 USD per Mtok
    assert record["served_model"] == "claude-sonnet-5-5"


def test_the_model_comes_from_claude_judge_model_and_an_unknown_one_has_no_price(sdk, monkeypatch):
    seen, state = sdk
    monkeypatch.setenv("CLAUDE_JUDGE_MODEL", "claude-haiku-5-5")
    state["handler"] = lambda req, body: httpx2.Response(200, json=message_json(json.dumps(ANSWER), model="claude-haiku-5-5"))
    record = run("en")
    assert seen[0][2]["model"] == "claude-haiku-5-5" and record["model"] == "claude-haiku-5-5"
    assert record["cost_usd"] == pytest.approx(3300 * 0.10 / 1e6 + 700 * 0.50 / 1e6)

    monkeypatch.setenv("CLAUDE_JUDGE_MODEL", "claude-next-9")
    state["handler"] = lambda req, body: httpx2.Response(200, json=message_json(json.dumps(ANSWER), model="claude-next-9"))
    record = run("en")
    assert record["status"] == "ok" and record["cost_usd"] is None and record["input_tokens"] == 3300


def test_the_judge_steps_down_the_fallback_ladder_like_mode_a(sdk):
    seen, state = sdk

    def handler(req, body):
        if "anthropic-beta" in req.headers:
            return httpx2.Response(400, json={"type": "error", "error": {"type": "invalid_request_error", "message": "fallbacks: no"}})
        return httpx2.Response(200, json=message_json(json.dumps(ANSWER)))

    state["handler"] = handler
    record = run("en")
    assert record["status"] == "ok" and record["fallback_form"] == "none" and len(record["fallback_attempts"]) == 2
    assert len(seen) == 3 and "fallbacks" not in seen[2][2] and seen[2][2]["output_config"]["effort"] == "medium"
    assert [p["type"] for p in seen[2][2]["messages"][0]["content"]] == ["text", "image", "text", "image", "text"]


def test_the_judge_uses_the_mode_a_client_settings(sdk):
    seen, _ = sdk
    run("en")
    assert seen.kwargs[0]["max_retries"] == 2 and seen.kwargs[0]["timeout"] == judge.TIMEOUT_S
    assert judge.TIMEOUT_S <= 600


def walk_objects(node, visit):
    if isinstance(node, dict):
        if node.get("type") == "object":
            visit(node)
        for child in node.values():
            walk_objects(child, visit)
    elif isinstance(node, list):
        for child in node:
            walk_objects(child, visit)


def test_the_schema_is_strict_enough_for_structured_outputs():
    count = []

    def check(node):
        assert node["additionalProperties"] is False
        assert sorted(node["required"]) == sorted(node["properties"])
        count.append(1)

    walk_objects(judge.JUDGE_SCHEMA, check)
    assert len(count) == 6  # the root and the five issue objects
    assert set(judge.JUDGE_SCHEMA["required"]) == {
        "usability", "coverage", "legibility_issues", "structure_issues", "altered_numbers",
        "untranslated", "mistranslated", "summary",
    }  # fmt: skip
    props = judge.JUDGE_SCHEMA["properties"]
    assert set(props["legibility_issues"]["items"]["required"]) == {"where", "output_text", "problem", "major"}
    assert props["structure_issues"]["items"]["properties"]["type"]["enum"] == [
        "duplicated_row", "missing_row", "shifted", "misaligned_marks", "other",
    ]  # fmt: skip
    assert set(props["altered_numbers"]["items"]["required"]) == {"source", "output", "where"}
    assert set(props["untranslated"]["items"]["required"]) == {"text", "where", "major"}
    assert set(props["mistranslated"]["items"]["required"]) == {"output_text", "source_text", "problem"}


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
        (lambda req, body: httpx2.Response(200, json=message_json(json.dumps({"summary": "no scores"}))), "schema"),
        (lambda req, body: httpx2.Response(200, json=message_json(json.dumps({**ANSWER, "usability": "high"}))), "schema"),
        (lambda req, body: httpx2.Response(200, json=message_json(json.dumps({**ANSWER, "legibility_issues": "x"}))), "schema"),
        (
            lambda req, body: httpx2.Response(
                200, json=message_json(json.dumps({**ANSWER, "structure_issues": [{"type": "sideways", "detail": "x"}]}))
            ),
            "schema",
        ),
        (
            lambda req, body: httpx2.Response(
                200, json=message_json(json.dumps({**ANSWER, "untranslated": [{"text": "x", "where": "y"}]}))  # no `major`
            ),
            "schema",
        ),
    ],
)
def test_a_judge_failure_is_unavailable_and_does_not_raise(sdk, handler, expected):
    _, state = sdk
    state["handler"] = handler
    record = run("en")
    assert record["status"] == "unavailable" and expected in record["error"]
    assert record["model"] == "claude-sonnet-5-5" and "usability" not in record


def test_a_connection_error_and_a_missing_key_are_unavailable_too(sdk, monkeypatch):
    _, state = sdk

    def dead(req, body):
        raise httpx2.ConnectError("no route to host", request=req)

    state["handler"] = dead
    record = run("en")
    assert record["status"] == "unavailable" and "connection" in record["error"].lower()

    monkeypatch.delenv("ANTHROPIC_API_KEY")
    state["handler"] = lambda req, body: pytest.fail("no key: the API must not be called")
    record = run("en")
    assert record["status"] == "unavailable" and "ANTHROPIC_API_KEY" in record["error"]


def test_an_unexpected_exception_inside_the_judge_is_contained(sdk, monkeypatch):
    def boom(*a, **k):
        raise KeyError("something unexpected")

    monkeypatch.setattr(judge, "build_user_text", boom)
    record = run("en")
    assert record["status"] == "unavailable" and "KeyError" in record["error"]


def test_an_output_that_is_not_an_image_is_unavailable_not_a_crash(sdk):
    record = run("en", output=b"definitely not a picture")
    assert record["status"] == "unavailable" and record["error"]


def test_errors_never_carry_the_api_key(sdk):
    _, state = sdk
    state["handler"] = lambda req, body: error_response(500, "failed for key test-anthropic-key-0000000000")
    record = run("en")
    assert "test-anthropic-key-0000000000" not in json.dumps(record) and "***" in record["error"]


def test_the_scores_are_clamped_into_zero_to_one(sdk):
    _, state = sdk
    for raw, expected in ((1.7, 1.0), (-0.3, 0.0), (0.5, 0.5), (1, 1.0)):
        body = {**ANSWER, "usability": raw, "coverage": raw}
        state["handler"] = lambda req, b, body=body: httpx2.Response(200, json=message_json(json.dumps(body)))
        record = run("en")
        assert (record["usability"], record["coverage"]) == (expected, expected)


def test_extra_fields_are_ignored_and_the_issue_lists_default_to_empty(sdk):
    _, state = sdk
    minimal = {"usability": 0.95, "coverage": 0.9, "summary": "fine", "extra": 1}
    state["handler"] = lambda req, body: httpx2.Response(200, json=message_json(json.dumps(minimal)))
    record = run("en")
    assert record["status"] == "ok" and record["legibility_issues"] == [] and record["untranslated"] == []


def test_a_missing_output_image_is_skipped_without_calling_claude(sdk):
    seen, _ = sdk
    record = run("en", output=None)
    assert record["status"] == "skipped" and "no output image" in record["note"] and seen == []
    assert run("en", output=b"")["status"] == "skipped"


def test_a_source_without_ocr_text_is_still_judged_from_the_pictures(sdk):
    seen, _ = sdk
    record = run("en", source=ocr(text=""))
    assert record["status"] == "ok" and record["source_lines"] == 0
    assert "(OCR found no text in Image 1)" in seen[0][2]["messages"][0]["content"][-1]["text"]
    record = run("en", source=None)  # Vision failed for the source: the judge works from the pictures alone
    assert record["status"] == "ok" and len(seen) == 2
    assert [p["type"] for p in seen[1][2]["messages"][0]["content"]].count("image") == 2


# ---------------------------------------------------------------- the input


def test_ocr_lines_use_the_full_text_lines_else_the_paragraphs():
    assert judge.ocr_lines(SOURCE) == ["TEL 77446716", "営業時間", "メニュー", "ご飯 定食"]
    no_text = ocr(("Hello", "en"), ("World", "en"), text="")
    assert judge.ocr_lines(no_text) == ["Hello World"]  # one paragraph (all stand-in words share para 0)
    assert judge.ocr_lines(ocr(text="")) == [] and judge.ocr_lines(None) == []


def test_long_pages_are_cut_and_the_record_says_so(sdk):
    seen, _ = sdk
    big = ocr(("x", "en"), text="\n".join(f"line {i}" for i in range(judge.MAX_LINES + 50)))
    record = run("en", source=big)
    assert record["status"] == "ok" and record["truncated"] is True and record["source_lines"] == judge.MAX_LINES
    text = seen[0][2]["messages"][0]["content"][-1]["text"]
    assert f"S{judge.MAX_LINES}:" in text and f"S{judge.MAX_LINES + 1}:" not in text
    wide = ocr(("x", "en"), text="y" * (judge.MAX_LINE_CHARS + 100))
    assert run("en", source=wide)["truncated"] is True


def test_a_picture_with_a_long_edge_over_1568_is_downscaled_whatever_its_orientation(sdk):
    seen, _ = sdk
    run("en", image=picture((1000, 3100), (1, 2, 3)), output=png_of((2000, 500)))
    blocks = [p for p in seen[0][2]["messages"][0]["content"] if p["type"] == "image"]
    assert decoded(blocks[0]).size == (506, 1568) and decoded(blocks[1]).size == (1568, 392)


# ---------------------------------------------------------------- mock mode and logging


def test_mock_mode_has_a_canned_answer_and_is_fully_offline(monkeypatch):
    monkeypatch.setenv("LAB_MOCK", "1")
    monkeypatch.setattr(anthropic, "Anthropic", lambda **kw: pytest.fail("mock mode must not build an SDK client"))
    record = run("en")
    assert record["status"] == "ok" and record["fallback_form"] == "mock"
    assert (record["usability"], record["coverage"]) == (1.0, 1.0)
    assert record["legibility_issues"] == [] and record["untranslated"] == [] and record["altered_numbers"] == []
    assert (record["input_tokens"], record["output_tokens"]) == (3400, 420)
    assert record["cost_usd"] == pytest.approx(3400 * 2.0 / 1e6 + 420 * 10.0 / 1e6)
    assert run("en", output=None)["status"] == "skipped"


def test_nothing_about_the_ocr_text_or_the_pictures_is_logged_at_info(sdk, caplog):
    with caplog.at_level(logging.INFO):
        run("en")
    logged = " ".join(r.getMessage() for r in caplog.records)
    assert "stage=judge" in logged and "usability=0.42" in logged
    for secret in ("77446716", "営業時間", "Manbar", "test-anthropic-key", "/9j/", "Image 1 is"):
        assert secret not in logged


def readme_text():
    readme = Path(__file__).resolve().parents[1] / "README.md"
    if not readme.exists():  # the container image does not carry the README
        pytest.skip("README.md not available")
    return readme.read_text(encoding="utf-8")


def test_readme_shows_the_judge_prompt_that_the_code_uses():
    text = readme_text()
    assert judge.SYSTEM_PROMPT in text
    assert judge.ORIGINAL_LABEL in text and judge.OUTPUT_LABEL in text
    assert "CLAUDE_JUDGE_MODEL" in text and "claude-sonnet-5-5" in text
    for field in judge.JUDGE_SCHEMA["required"]:
        assert f"`{field}`" in text, field


def test_env_example_and_compose_know_the_judge_model_and_the_mode_a_retry():
    root = Path(__file__).resolve().parents[1]
    if not (root / ".env.example").exists():
        pytest.skip("deployment files not available")
    env = (root / ".env.example").read_text(encoding="utf-8")
    compose = (root / "docker-compose.yml").read_text(encoding="utf-8")
    assert "CLAUDE_JUDGE_MODEL=claude-sonnet-5-5" in env and "CLAUDE_JUDGE_MODEL" in compose
    assert "MODE_A_RETRY=1" in env and "MODE_A_RETRY" in compose


def test_parse_answer_rejects_what_the_schema_does_not_allow():
    with pytest.raises(ModeError, match="not valid JSON"):
        judge.parse_answer("nope")
    with pytest.raises(ModeError, match="schema"):
        judge.parse_answer(json.dumps({"coverage": 0.9}))
    assert judge.parse_answer(json.dumps(ANSWER)).usability == 0.42
