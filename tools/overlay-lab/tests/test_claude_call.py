"""The exact shape of the Claude request, the fallback-form ladder and error handling."""

import base64
import io
import json

import anthropic
import httpx2
import pytest
from helpers import rect, word
from PIL import Image

from app import claude_units
from app.imaging import load_image
from app.models import ClaudeUnit, ModeError, OcrResult

CANNED = {
    "units": [
        {
            "word_ids": ["w0001", "w0002"],
            "source_text": "價格表",
            "translation": "Price list",
            "direction": "horizontal",
            "rotated": False,
            "role": "heading",
            "keep": False,
            "notes": "",
        },
        {
            "word_ids": ["w0002", "w9999"],  # duplicate + unknown: rejected by validation
            "source_text": "x",
            "translation": "y",
            "direction": "horizontal",
            "rotated": False,
            "role": "other",
            "keep": False,
            "notes": "",
        },
    ]
}


COVERING = json.dumps(
    {
        "units": [
            {
                "word_ids": ["w0001", "w0002"],
                "source_text": "價格表",
                "translation": "Price list",
                "direction": "horizontal",
                "rotated": False,
                "role": "heading",
                "keep": False,
                "notes": "",
            }
        ]
    }
)  # both words of the `setup` fixture are in this one unit: no coverage pass


class Usage:
    input_tokens = 1234
    output_tokens = 321


class Block:
    type = "text"

    def __init__(self, text):
        self.text = text


class Response:
    def __init__(self, text=None, stop_reason="end_turn", model="claude-opus-5-5"):
        self.content = [Block(text if text is not None else json.dumps(CANNED))]
        self.usage = Usage()
        self.stop_reason = stop_reason
        self.stop_details = None
        self.model = model


def bad_request(msg="rejected"):
    request = httpx2.Request("POST", "https://api.anthropic.com/v1/messages")
    return anthropic.BadRequestError(msg, response=httpx2.Response(400, request=request), body=None)


class FakeClient:
    """Records every create() call; `plan` maps form name -> exception or Response."""

    def __init__(self, plan):
        self.plan = plan
        self.calls = []
        client = self

        class Beta:
            class messages:
                @staticmethod
                def create(**kw):
                    form = "default" if kw.get("fallbacks") == "default" else "array"
                    client.calls.append((form, kw))
                    out = client.plan[form]
                    if isinstance(out, Exception):
                        raise out
                    return out

        class Plain:
            @staticmethod
            def create(**kw):
                client.calls.append(("none", kw))
                out = client.plan["none"]
                if isinstance(out, Exception):
                    raise out
                return out

        self.beta = Beta
        self.messages = Plain


@pytest.fixture
def setup(monkeypatch):
    monkeypatch.setenv("ANTHROPIC_API_KEY", "test-anthropic-key-0000000000")
    big = Image.new("RGB", (3000, 2000), (240, 240, 240))
    buf = io.BytesIO()
    big.save(buf, "PNG")
    image = load_image(buf.getvalue())
    ocr = OcrResult(
        width=3000,
        height=2000,
        words=[word(1, "價格", rect(10, 10, 80, 40)), word(2, "表", rect(90, 10, 130, 40))],
    )

    def install(plan):
        fake = FakeClient(plan)
        monkeypatch.setattr(anthropic, "Anthropic", lambda **kw: fake)
        return fake

    return image, ocr, install


def test_default_form_uses_the_beta_endpoint_with_fallbacks_default_and_json_schema(setup):
    image, ocr, install = setup
    fake = install({"default": Response()})
    call = claude_units.request_units(image, ocr, "en")

    assert [form for form, _ in fake.calls] == ["default"]
    kw = fake.calls[0][1]
    assert kw["betas"] == ["server-side-fallback-2026-07-01"]
    assert kw["fallbacks"] == "default"
    assert kw["model"] == "claude-opus-5-5"
    assert kw["max_tokens"] == 32000
    assert "thinking" not in kw
    assert kw["output_config"]["effort"] == "high"
    fmt = kw["output_config"]["format"]
    assert fmt["type"] == "json_schema" and fmt["schema"] is claude_units.UNITS_SCHEMA
    assert kw["system"] == claude_units.SYSTEM_PROMPT

    content = kw["messages"][0]["content"]
    assert [part["type"] for part in content] == ["image", "text"]
    src = content[0]["source"]
    assert src["type"] == "base64" and src["media_type"] == "image/jpeg"
    sent = Image.open(io.BytesIO(base64.b64decode(src["data"])))
    assert sent.format == "JPEG" and max(sent.size) == 1568  # long side downscaled from 3000
    text = content[1]["text"]
    assert "Target language: English (en)" in text
    assert "Original image size: 3000 x 2000 px" in text
    assert "ORIGINAL pixel coordinates" in text
    assert "w0001 10 10 70 30 價格" in text and "w0002 90 10 40 30 表" in text

    assert call.fallback_form == "default"
    assert (call.input_tokens, call.output_tokens) == (1234, 321)
    assert call.cost_usd == pytest.approx(1234 * 4 / 1e6 + 321 * 20 / 1e6)
    assert [u.word_ids for u in call.units] == [["w0001", "w0002"]]  # second unit rejected
    assert call.notes and any("rejected" in n for n in call.notes)
    assert call.sent_image_size == (1568, 1045)


def test_falls_back_to_the_array_form_when_the_default_form_is_rejected(setup):
    image, ocr, install = setup
    fake = install({"default": bad_request("fallbacks: unknown"), "array": Response()})
    call = claude_units.request_units(image, ocr, "ja")
    assert [form for form, _ in fake.calls] == ["default", "array"]
    kw = fake.calls[1][1]
    assert kw["betas"] == ["server-side-fallback-2026-06-01"]
    assert kw["fallbacks"] == [{"model": "claude-opus-4-8"}]
    assert call.fallback_form == "array"
    assert call.attempts and call.attempts[0].startswith("default: BadRequestError")


def test_falls_back_to_plain_messages_create_when_the_sdk_rejects_the_keyword(setup):
    image, ocr, install = setup
    fake = install({"default": TypeError("unexpected keyword 'fallbacks'"), "array": TypeError("nope"), "none": Response()})
    call = claude_units.request_units(image, ocr, "en")
    assert [form for form, _ in fake.calls] == ["default", "array", "none"]
    kw = fake.calls[2][1]
    assert "betas" not in kw and "fallbacks" not in kw
    assert kw["output_config"]["effort"] == "high"
    assert call.fallback_form == "none" and len(call.attempts) == 2


def test_every_form_rejected_reports_all_attempts(setup):
    image, ocr, install = setup
    install({"default": bad_request("a"), "array": bad_request("b"), "none": bad_request("c")})
    with pytest.raises(ModeError, match="every form"):
        claude_units.request_units(image, ocr, "en")


def test_refusal_is_an_error_for_mode_a(setup):
    image, ocr, install = setup
    resp = Response(stop_reason="refusal")

    class Details:
        category = "cyber"
        explanation = "declined"

    resp.stop_details = Details()
    install({"default": resp})
    with pytest.raises(ModeError, match="refused.*cyber"):
        claude_units.request_units(image, ocr, "en")


def test_max_tokens_stop_is_reported_instead_of_parsing_truncated_json(setup):
    image, ocr, install = setup
    install({"default": Response(text='{"units": [', stop_reason="max_tokens")})
    with pytest.raises(ModeError, match="max_tokens"):
        claude_units.request_units(image, ocr, "en")


def test_non_json_text_is_a_clear_error(setup):
    image, ocr, install = setup
    install({"default": Response(text="Sure! Here you go")})
    with pytest.raises(ModeError, match="not valid JSON"):
        claude_units.request_units(image, ocr, "en")


def test_other_http_errors_are_not_retried_through_the_ladder(setup):
    image, ocr, install = setup
    request = httpx2.Request("POST", "https://api.anthropic.com/v1/messages")
    auth = anthropic.AuthenticationError("bad key", response=httpx2.Response(401, request=request), body=None)
    fake = install({"default": auth})
    with pytest.raises(ModeError, match="401"):
        claude_units.request_units(image, ocr, "en")
    assert len(fake.calls) == 1


def test_unknown_served_model_still_returns_tokens_without_a_price(setup, monkeypatch):
    image, ocr, install = setup
    monkeypatch.setenv("CLAUDE_MODEL", "claude-opus-5")
    install({"default": Response(model="claude-opus-5")})
    call = claude_units.request_units(image, ocr, "en")
    assert call.cost_usd is None and call.input_tokens == 1234


def test_missing_api_key_is_a_friendly_error(setup, monkeypatch):
    image, ocr, install = setup
    monkeypatch.delenv("ANTHROPIC_API_KEY")
    install({"default": Response()})
    with pytest.raises(ModeError, match="ANTHROPIC_API_KEY"):
        claude_units.request_units(image, ocr, "en")


# ------------------------------------------------------------------ the real SDK, mocked at the HTTP layer


def _message_json(text: str) -> dict:
    return {
        "id": "msg_test",
        "type": "message",
        "role": "assistant",
        "model": "claude-opus-5-5",
        "content": [{"type": "text", "text": text}],
        "stop_reason": "end_turn",
        "stop_sequence": None,
        "usage": {"input_tokens": 777, "output_tokens": 55},
    }


def _install_real_sdk(monkeypatch, handler):
    """Real anthropic.Anthropic whose HTTP transport is a MockTransport (nothing leaves the box)."""
    seen = []
    real = anthropic.Anthropic

    def transport(request: httpx2.Request) -> httpx2.Response:
        seen.append((request.url.path, dict(request.headers), json.loads(request.content)))
        return handler(request, seen[-1][2])

    def factory(**kw):
        # the timeout of the production client is passed on: a client left at the SDK default would refuse a
        # non-streaming request of 32000 tokens ("Streaming is required ..."), one with an explicit timeout does not
        extra = {"timeout": kw["timeout"]} if "timeout" in kw else {}
        return real(
            api_key="test-anthropic-key-0000000000",
            max_retries=0,
            http_client=anthropic.DefaultHttpxClient(transport=httpx2.MockTransport(transport)),
            **extra,
        )

    monkeypatch.setattr(anthropic, "Anthropic", factory)
    return seen


def test_installed_sdk_serialises_the_default_fallback_form_on_the_wire(setup, monkeypatch):
    image, ocr, _ = setup
    seen = _install_real_sdk(monkeypatch, lambda req, body: httpx2.Response(200, json=_message_json(COVERING)))
    call = claude_units.request_units(image, ocr, "en")
    assert call.fallback_form == "default" and call.attempts == []
    path, headers, body = seen[0]
    assert path == "/v1/messages"
    assert "server-side-fallback-2026-07-01" in headers["anthropic-beta"]
    assert body["fallbacks"] == "default"
    assert body["model"] == "claude-opus-5-5" and body["max_tokens"] == 32000
    assert body["output_config"]["effort"] == "high"
    assert body["output_config"]["format"]["type"] == "json_schema"
    assert body["output_config"]["format"]["schema"]["additionalProperties"] is False
    assert "thinking" not in body
    assert [p["type"] for p in body["messages"][0]["content"]] == ["image", "text"]


def test_installed_sdk_steps_down_to_the_array_form_then_plain_on_http_400(setup, monkeypatch):
    image, ocr, _ = setup

    def handler(req, body):
        beta = req.headers.get("anthropic-beta", "")
        if "server-side-fallback-2026-07-01" in beta or "server-side-fallback-2026-06-01" in beta:
            error = {"type": "invalid_request_error", "message": "fallbacks: not supported"}
            return httpx2.Response(400, json={"type": "error", "error": error})
        return httpx2.Response(200, json=_message_json(COVERING))

    seen = _install_real_sdk(monkeypatch, handler)
    call = claude_units.request_units(image, ocr, "en")
    assert call.fallback_form == "none" and len(call.attempts) == 2
    assert len(seen) == 3
    assert "server-side-fallback-2026-06-01" in seen[1][1]["anthropic-beta"]
    assert seen[1][2]["fallbacks"] == [{"model": "claude-opus-4-8"}]
    assert "fallbacks" not in seen[2][2] and "anthropic-beta" not in seen[2][1]
    assert seen[2][2]["output_config"]["effort"] == "high"


# ------------------------------------------------------------------ round 5: the retry paragraph and the Claude slots


def test_the_planner_prompt_explains_the_four_new_fields_and_the_table_rules():
    prompt = claude_units.SYSTEM_PROMPT
    for needle in (
        "- short:",
        "at most about 60% of the characters",
        "- align: left | center | right",
        "- weight: regular | bold",
        "- importance: major | minor",
        "Table rows stay one unit per cell, in the same order as in the picture",
        "price-like or number-only cell",
        "brand names and logos",
        "drawn rotated by 90 degrees",
        "Look at the picture, not only at the OCR text, to decide alignment and weight",
    ):
        assert needle in prompt, needle


def test_the_feedback_paragraph_is_appended_to_the_word_list_of_the_request(setup):
    image, ocr, install = setup
    fake = install({"default": Response()})
    claude_units.request_units(image, ocr, "en")
    claude_units.request_units(image, ocr, "en", feedback="This is a second attempt. Fix the heading.")
    first = fake.calls[0][1]["messages"][0]["content"][1]["text"]
    second = fake.calls[1][1]["messages"][0]["content"][1]["text"]
    assert "second attempt" not in first
    assert second.startswith(first) and second.endswith("This is a second attempt. Fix the heading.")
    assert second.count("w0001 10 10 70 30 價格") == 1  # the same word list, the paragraph after it


def judge_findings(**fields):
    from scenario import judge_record

    return {"judge": judge_record(**fields)}


def test_build_feedback_names_what_the_judge_found_and_the_units_that_did_not_fit():
    verification = judge_findings(
        garbled=[("price pill", "Manbar Price", True), ("logo", "Sinoe", False)],
        untranslated=[("ご飯", True), ("footnote", False)],
        altered=[("77446716", "99446916")],
        rows=[("duplicated_row", "scallion rice")],
    )
    problems = [
        {"source_text": "招牌套餐", "overflowed": True, "action": "translated"},
        {"source_text": '請勿 "打擾"\n謝謝', "truncated": True, "action": "translated"},
        {"source_text": "小字", "action": "skipped", "skip_reason": "too small"},
    ]
    text = claude_units.build_feedback(verification, problems)
    assert text.startswith(claude_units.FEEDBACK_HEAD) and text.endswith(claude_units.FEEDBACK_TAIL)
    assert '"Manbar Price" (price pill)' in text
    assert "Sinoe" not in text and "footnote" not in text  # minor findings are not worth a retry
    assert '"ご飯"' in text and '"77446716" became "99446916"' in text and '"scallion rice"' in text
    assert '"招牌套餐" (did not fit its box)' in text and "(was cut off)" in text and '"小字" (had to be drawn too small)' in text
    assert "\"請勿 '打擾' 謝謝\"" in text  # quotes and newlines of OCR text are neutralised
    assert "The reviewer's summary:" in text


def test_build_feedback_caps_every_kind_at_ten_and_is_empty_when_there_is_nothing_to_say():
    many = judge_findings(garbled=[(f"w{i}", f"junk{i}", True) for i in range(15)])
    text = claude_units.build_feedback(many, [{"source_text": f"u{i}", "overflowed": True} for i in range(15)])
    assert '"junk9"' in text and "junk10" not in text and '"u9"' in text and '"u10"' not in text
    assert claude_units.build_feedback(None, []) == ""
    assert claude_units.build_feedback({"judge": {"status": "unavailable"}}, []) == ""
    assert claude_units.build_feedback({"judge": None}, [{"source_text": "x", "overflowed": True}]) != ""  # render problems alone


def test_the_claude_slots_are_four_and_shared_by_the_planner_and_the_judge():
    held = [claude_units.CLAUDE_SLOTS.acquire(blocking=False) for _ in range(4)]
    assert all(held) and not claude_units.CLAUDE_SLOTS.acquire(blocking=False)
    for _ in held:
        claude_units.CLAUDE_SLOTS.release()


def test_at_most_four_claude_calls_are_in_flight_planner_and_judge_together(setup, monkeypatch):
    import threading
    import time

    from app import judge

    image, ocr, _ = setup
    state = {"active": 0, "peak": 0, "calls": 0}
    lock = threading.Lock()
    judge_answer = json.dumps(
        {"usability": 1, "coverage": 1, "legibility_issues": [], "structure_issues": [], "altered_numbers": [],
         "untranslated": [], "mistranslated": [], "summary": "ok"}  # fmt: skip
    )

    class SlowClient:
        class beta:  # noqa: N801
            class messages:  # noqa: N801
                @staticmethod
                def create(**kw):
                    with lock:
                        state["active"] += 1
                        state["calls"] += 1
                        state["peak"] = max(state["peak"], state["active"])
                    time.sleep(0.12)
                    with lock:
                        state["active"] -= 1
                    is_judge = "quality judge" in kw["system"]
                    return Response(text=judge_answer if is_judge else COVERING, model=kw["model"])

    monkeypatch.setattr(anthropic, "Anthropic", lambda **kw: SlowClient())
    source = Image.new("RGB", (200, 100))
    png = io.BytesIO()
    Image.new("RGB", (200, 100)).save(png, "PNG")
    results = []

    def planner():
        try:
            claude_units.request_units(image, ocr, "en")
            results.append("ok")
        except Exception as exc:  # noqa: BLE001
            results.append(repr(exc))

    def judging():
        results.append("ok" if judge.judge_output(None, source, png.getvalue(), "en")["status"] == "ok" else "judge failed")

    threads = [threading.Thread(target=planner if i % 2 else judging) for i in range(12)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=30)
    assert state["calls"] == 12 and results == ["ok"] * 12
    assert state["peak"] == 4  # twelve calls, never more than four at once, and four really do run together


# ------------------------------------------------------------------ round 6: the token budget, the coverage pass, the group field


class SeqClient:
    """Answers the planner's calls one after the other (a Response, or an exception to raise) and records them."""

    def __init__(self, answers):
        self.answers = list(answers)
        self.requests = []
        client = self

        class Beta:
            class messages:  # noqa: N801
                @staticmethod
                def create(**kw):
                    client.requests.append(kw)
                    out = client.answers.pop(0)
                    if isinstance(out, Exception):
                        raise out
                    return out

        self.beta = Beta

    def texts(self):
        return [kw["messages"][0]["content"][1]["text"] for kw in self.requests]


def planner_unit(ids, text="x", translation="y", **extra):
    unit = {
        "word_ids": ids, "source_text": text, "translation": translation, "direction": "horizontal",
        "rotated": False, "role": "label", "keep": False, "notes": "", "short": "", "align": "left",
        "weight": "regular", "importance": "major", "group": "",
    }  # fmt: skip
    return {**unit, **extra}


def answer(*units, tokens=None, model="claude-opus-5-5"):
    resp = Response(text=json.dumps({"units": list(units)}), model=model)
    if tokens:
        resp.usage = type("U", (), {"input_tokens": tokens[0], "output_tokens": tokens[1]})
    return resp


@pytest.fixture
def ten_words(monkeypatch):
    """Ten OCR words w0001..w0010 (nine of them with text, plus the `install` helper for a scripted client)."""
    monkeypatch.setenv("ANTHROPIC_API_KEY", "test-anthropic-key-0000000000")
    image = load_image(_png(800, 600))
    words = [word(i, f"字{i}", rect(10, 10 + 30 * i, 60, 30 + 30 * i)) for i in range(1, 11)]
    ocr = OcrResult(width=800, height=600, words=words)

    def install(answers):
        fake = SeqClient(answers)
        monkeypatch.setattr(anthropic, "Anthropic", lambda **kw: fake)
        return fake

    return image, ocr, install


def _png(w, h):
    buf = io.BytesIO()
    Image.new("RGB", (w, h), (240, 240, 240)).save(buf, "PNG")
    return buf.getvalue()


def ids(*numbers):
    return [f"w{n:04d}" for n in numbers]


def test_the_planner_token_budget_is_32000():
    assert claude_units.MAX_TOKENS == 32000


def test_max_tokens_error_names_the_budget_and_the_tokens_the_failed_call_spent(setup):
    image, ocr, install = setup
    resp = Response(text='{"units": [', stop_reason="max_tokens")
    resp.usage = type("U", (), {"input_tokens": 9000, "output_tokens": 32000})
    install({"default": resp})
    with pytest.raises(ModeError) as err:
        claude_units.request_units(image, ocr, "en")
    message = str(err.value)
    assert "max_tokens=32000" in message and "32000 output tokens" in message and "before finishing the JSON" in message


def test_max_tokens_error_without_usage_numbers_still_reads_well(setup):
    image, ocr, install = setup
    resp = Response(text="{", stop_reason="max_tokens")
    resp.usage = None
    install({"default": resp})
    with pytest.raises(ModeError, match=r"max_tokens=32000 before finishing the JSON; "):
        claude_units.request_units(image, ocr, "en")


def test_coverage_is_judged_on_words_with_text_and_ten_percent_is_the_limit():
    assert claude_units.COVERAGE_MIN_SHARE == 0.90
    short = claude_units.coverage_short
    assert not short(10, 0) and not short(10, 1)  # 90% listed is enough: "more than 10% left out" is the trigger
    assert short(10, 2) and short(100, 11) and not short(100, 10) and not short(277, 27) and short(277, 28)
    assert not short(0, 0)


def test_a_first_answer_that_leaves_out_most_words_gets_one_coverage_pass_with_only_those_words(ten_words):
    image, ocr, install = ten_words
    fake = install([
        answer(planner_unit(ids(1, 2, 3), "A", "a"), planner_unit(ids(4, 5), "B", "b"), tokens=(1000, 300)),
        answer(planner_unit(ids(6, 7, 8), "C", "c"), planner_unit(ids(9), "D", "d"), planner_unit(ids(10), "E", "e", keep=True),
               tokens=(400, 120)),
    ])  # fmt: skip
    call = claude_units.request_units(image, ocr, "en")
    assert len(fake.requests) == 2  # exactly one more call, not a loop
    first, second = fake.texts()
    for n in range(1, 11):
        assert f"w{n:04d} " in first
    word_part = second.split("---\n")[1]  # the word list of the second request
    assert [line.split()[0] for line in word_part.splitlines() if line.startswith("w0")] == ids(6, 7, 8, 9, 10)
    assert "coverage pass" in second and "5 words" in second and "Vertical columns are one unit per column" in second
    assert second.endswith("each id in exactly one unit.")
    assert [u.word_ids for u in call.units] == [ids(1, 2, 3), ids(4, 5), ids(6, 7, 8), ids(9), ids(10)]
    assert call.coverage == {
        "pass": True, "words": 10, "unlisted_before": 5, "unlisted_after": 0, "added_units": 3, "added_words": 5, "error": None,
    }  # fmt: skip
    assert (call.input_tokens, call.output_tokens) == (1400, 420)  # both calls are counted
    assert call.cost_usd == pytest.approx(1400 * 4 / 1e6 + 420 * 20 / 1e6)
    assert any("coverage pass: 5 of 10 words" in n for n in call.notes)


def test_the_second_request_sends_the_same_image_and_the_same_system_prompt(ten_words):
    image, ocr, install = ten_words
    fake = install([answer(planner_unit(ids(1, 2), "A", "a")), answer(planner_unit(ids(3, 4, 5, 6, 7, 8, 9, 10)))])
    claude_units.request_units(image, ocr, "en")
    first, second = fake.requests
    assert first["messages"][0]["content"][0] == second["messages"][0]["content"][0]  # the picture
    assert first["system"] == second["system"] == claude_units.SYSTEM_PROMPT
    assert first["max_tokens"] == second["max_tokens"] == 32000 and first["model"] == second["model"]


def test_no_coverage_pass_when_nine_of_ten_words_are_in_a_unit(ten_words):
    image, ocr, install = ten_words
    fake = install([answer(planner_unit(ids(1, 2, 3, 4, 5)), planner_unit(ids(6, 7, 8, 9)))])
    call = claude_units.request_units(image, ocr, "en")
    assert len(fake.requests) == 1 and call.coverage["pass"] is False and call.coverage["unlisted_before"] == 1
    assert call.coverage["added_units"] == 0 and call.coverage["error"] is None


def test_eight_of_ten_words_is_short_of_the_limit_and_asks_again(ten_words):
    image, ocr, install = ten_words
    fake = install([answer(planner_unit(ids(1, 2, 3, 4, 5, 6, 7, 8))), answer(planner_unit(ids(9, 10)))])
    call = claude_units.request_units(image, ocr, "en")
    assert len(fake.requests) == 2 and call.coverage["pass"] is True and call.coverage["added_words"] == 2
    assert [u.word_ids for u in call.units][-1] == ids(9, 10)


def test_words_without_text_do_not_count_as_left_out(monkeypatch):
    monkeypatch.setenv("ANTHROPIC_API_KEY", "test-anthropic-key-0000000000")
    image = load_image(_png(400, 300))
    words = [word(i, f"字{i}", rect(10, 10 * i, 50, 10 * i + 8)) for i in range(1, 10)] + [word(10, "  ", rect(10, 200, 50, 210))]
    ocr = OcrResult(width=400, height=300, words=words)
    fake = SeqClient([answer(planner_unit(ids(1, 2, 3, 4, 5, 6, 7, 8, 9)))])
    monkeypatch.setattr(anthropic, "Anthropic", lambda **kw: fake)
    call = claude_units.request_units(image, ocr, "en")
    assert len(fake.requests) == 1 and call.coverage["words"] == 9 and call.coverage["unlisted_before"] == 0


def test_ids_the_second_answer_repeats_are_dropped_by_validation_and_unknown_ids_reject_a_unit(ten_words):
    image, ocr, install = ten_words
    install([
        answer(planner_unit(ids(1, 2, 3), "A", "a"), planner_unit(ids(4), "B", "b")),
        answer(planner_unit(ids(3, 5, 6), "C", "c"), planner_unit(ids(7, 99), "bad", "bad"), planner_unit(ids(8, 9, 10), "D", "d")),
    ])  # fmt: skip
    call = claude_units.request_units(image, ocr, "en")
    assert [u.word_ids for u in call.units] == [ids(1, 2, 3), ids(4), ids(5, 6), ids(8, 9, 10)]  # w0003 was used already, w0007 stays out
    assert call.coverage["unlisted_after"] == 1 and call.coverage["added_words"] == 5 and call.coverage["added_units"] == 2
    assert any("duplicate word id w0003" in n for n in call.notes) and any("unknown word ids" in n for n in call.notes)


def test_a_failing_second_call_keeps_the_first_answer_and_says_why(ten_words):
    image, ocr, install = ten_words
    spent = Response(text="{", stop_reason="max_tokens")
    install([answer(planner_unit(ids(1, 2, 3)), tokens=(1000, 300)), spent])
    call = claude_units.request_units(image, ocr, "en")
    assert [u.word_ids for u in call.units] == [ids(1, 2, 3)]
    assert call.coverage["pass"] is True and call.coverage["added_units"] == 0 and "max_tokens" in call.coverage["error"]
    assert (call.input_tokens, call.output_tokens) == (1000, 300)  # the failed call has no usable usage: nothing is added
    assert any("coverage pass failed" in n for n in call.notes)


def test_the_coverage_pass_counts_toward_the_same_claude_slots(ten_words, monkeypatch):
    image, ocr, install = ten_words
    install([answer(planner_unit(ids(1, 2, 3))), answer(planner_unit(ids(4, 5, 6, 7, 8, 9, 10)))])
    entered = []

    class Counting:
        def __enter__(self):
            entered.append(1)

        def __exit__(self, *exc):
            return False

    monkeypatch.setattr(claude_units, "CLAUDE_SLOTS", Counting())
    claude_units.request_units(image, ocr, "en")
    assert len(entered) == 2  # the planner call and the coverage pass each take a slot


def test_the_feedback_of_a_retry_goes_to_the_first_call_only(ten_words):
    image, ocr, install = ten_words
    fake = install([answer(planner_unit(ids(1, 2, 3))), answer(planner_unit(ids(4, 5, 6, 7, 8, 9, 10)))])
    claude_units.request_units(image, ocr, "en", feedback="This is a second attempt. Fix the heading.")
    first, second = fake.texts()
    assert first.endswith("Fix the heading.") and "Fix the heading." not in second and "coverage pass" in second


def test_the_planner_prompt_demands_every_word_one_unit_per_vertical_column_and_no_filler():
    prompt = claude_units.SYSTEM_PROMPT
    for needle in (
        "assign EVERY word of the list to a unit",
        "small cards, side notes",
        "only when it is pure decoration",
        "each id of the list is in exactly one unit",
        "every vertical column of CJK characters is always its own unit",
        "one column, one unit, one translation",
        "notes: an empty string unless something is really uncertain",
        "never fill short with a copy of the translation or with filler",
        "- group: a short id",
        "one font size and one weight",
        "Use an empty string for a unit that stands alone",
    ):
        assert needle in " ".join(prompt.split()), needle


def test_the_schema_requires_group_and_the_model_accepts_it_with_tidy_whitespace():
    item = claude_units.UNITS_SCHEMA["properties"]["units"]["items"]
    assert "group" in item["required"] and item["properties"]["group"] == {"type": "string"}
    assert set(item["required"]) == set(item["properties"])  # every property is required
    unit = ClaudeUnit(word_ids=["w1"], group="  menu   names ")
    assert unit.group == "menu names" and ClaudeUnit(word_ids=["w1"]).group == ""
    assert len(ClaudeUnit(word_ids=["w1"], group="g" * 100).group) == 40
    with pytest.raises(Exception):
        ClaudeUnit(word_ids=["w1"], groups="x")  # unknown fields stay forbidden
