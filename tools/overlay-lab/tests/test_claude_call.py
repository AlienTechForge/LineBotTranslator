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
from app.models import ModeError, OcrResult

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
    assert kw["max_tokens"] == 16000
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
        return real(
            api_key="test-anthropic-key-0000000000",
            max_retries=0,
            http_client=anthropic.DefaultHttpxClient(transport=httpx2.MockTransport(transport)),
        )

    monkeypatch.setattr(anthropic, "Anthropic", factory)
    return seen


def test_installed_sdk_serialises_the_default_fallback_form_on_the_wire(setup, monkeypatch):
    image, ocr, _ = setup
    empty = json.dumps({"units": []})
    seen = _install_real_sdk(monkeypatch, lambda req, body: httpx2.Response(200, json=_message_json(empty)))
    call = claude_units.request_units(image, ocr, "en")
    assert call.fallback_form == "default" and call.attempts == []
    path, headers, body = seen[0]
    assert path == "/v1/messages"
    assert "server-side-fallback-2026-07-01" in headers["anthropic-beta"]
    assert body["fallbacks"] == "default"
    assert body["model"] == "claude-opus-5-5" and body["max_tokens"] == 16000
    assert body["output_config"]["effort"] == "high"
    assert body["output_config"]["format"]["type"] == "json_schema"
    assert body["output_config"]["format"]["schema"]["additionalProperties"] is False
    assert "thinking" not in body
    assert [p["type"] for p in body["messages"][0]["content"]] == ["image", "text"]


def test_installed_sdk_steps_down_to_the_array_form_then_plain_on_http_400(setup, monkeypatch):
    image, ocr, _ = setup
    empty = json.dumps({"units": []})

    def handler(req, body):
        beta = req.headers.get("anthropic-beta", "")
        if "server-side-fallback-2026-07-01" in beta or "server-side-fallback-2026-06-01" in beta:
            error = {"type": "invalid_request_error", "message": "fallbacks: not supported"}
            return httpx2.Response(400, json={"type": "error", "error": error})
        return httpx2.Response(200, json=_message_json(empty))

    seen = _install_real_sdk(monkeypatch, handler)
    call = claude_units.request_units(image, ocr, "en")
    assert call.fallback_form == "none" and len(call.attempts) == 2
    assert len(seen) == 3
    assert "server-side-fallback-2026-06-01" in seen[1][1]["anthropic-beta"]
    assert seen[1][2]["fallbacks"] == [{"model": "claude-opus-4-8"}]
    assert "fallbacks" not in seen[2][2] and "anthropic-beta" not in seen[2][1]
    assert seen[2][2]["output_config"]["effort"] == "high"
