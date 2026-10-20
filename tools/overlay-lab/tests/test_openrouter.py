"""Mode B request / response handling against a mocked OpenRouter (no network)."""

import base64
import io
import json

import httpx2
import pytest
from PIL import Image

from app import openrouter
from app.imaging import load_image
from app.models import ModeError


def png_bytes(size=(64, 48), color=(10, 120, 200)) -> bytes:
    buf = io.BytesIO()
    Image.new("RGB", size, color).save(buf, "PNG")
    return buf.getvalue()


def data_url(raw: bytes, mime="image/png") -> str:
    return f"data:{mime};base64,{base64.b64encode(raw).decode()}"


@pytest.fixture
def router(monkeypatch):
    """Install a handler for every HTTP call openrouter.py makes and collect the requests."""
    monkeypatch.setenv("OPENROUTER_API_KEY", "or-test-key-123456")
    seen = []
    state = {"handler": None}

    def transport(request: httpx2.Request) -> httpx2.Response:
        body = json.loads(request.content) if request.content else None
        seen.append((request.method, request.url.path, dict(request.url.params), dict(request.headers), body))
        return state["handler"](request, body)

    monkeypatch.setattr(
        openrouter, "_client", lambda: httpx2.Client(transport=httpx2.MockTransport(transport))
    )
    openrouter._models_cache.update(at=0.0, data=None)
    return seen, state


def test_image_model_list_filters_on_output_modalities_sorts_and_caches(router):
    seen, state = router
    listing = {
        "data": [
            {"id": "z/vision-image", "name": "Z", "architecture": {"input_modalities": ["text", "image"], "output_modalities": ["image", "text"]}},
            {"id": "openai/gpt-4o", "architecture": {"input_modalities": ["text", "image"], "output_modalities": ["text"]}},
            {"id": "a/text-to-image", "name": "A", "architecture": {"input_modalities": ["text"], "output_modalities": ["image"]}},
            {"id": "google/gemini-2.5-flash-image", "name": "G", "architecture": {"input_modalities": ["image", "text"], "output_modalities": ["image", "text"]}},
        ]
    }
    state["handler"] = lambda req, body: httpx2.Response(200, json=listing)

    first = openrouter.list_image_models()
    assert first["models"] == ["google/gemini-2.5-flash-image", "a/text-to-image", "z/vision-image"]
    assert first["default"] == "google/gemini-2.5-flash-image"
    assert first["details"]["a/text-to-image"]["input_image"] is False
    assert first["details"]["z/vision-image"]["input_image"] is True
    assert "openai/gpt-4o" not in first["models"]
    assert first["cached"] is False and first["error"] is None
    method, path, params, _, _ = seen[0]
    assert (method, path) == ("GET", "/api/v1/models") and params == {"output_modalities": "all"}

    second = openrouter.list_image_models()
    assert second["cached"] is True and len(seen) == 1  # served from the 10 minute cache


def test_model_list_failure_still_offers_the_default(router):
    _, state = router
    state["handler"] = lambda req, body: httpx2.Response(500, json={"error": {"message": "boom"}})
    out = openrouter.list_image_models()
    assert out["models"] == ["google/gemini-2.5-flash-image"]
    assert "boom" in out["error"]


def test_default_model_comes_first_even_when_the_env_overrides_it(router, monkeypatch):
    _, state = router
    monkeypatch.setenv("OPENROUTER_IMAGE_MODEL", "b/custom-image")
    state["handler"] = lambda req, body: httpx2.Response(200, json={"data": [
        {"id": "a/one", "architecture": {"input_modalities": ["image"], "output_modalities": ["image"]}},
        {"id": "b/custom-image", "architecture": {"input_modalities": ["image"], "output_modalities": ["image"]}},
    ]})  # fmt: skip
    assert openrouter.list_image_models()["models"] == ["b/custom-image", "a/one"]


def test_chat_route_request_shape_and_image_extraction(router):
    seen, state = router
    out_png = png_bytes((100, 75))
    state["handler"] = lambda req, body: httpx2.Response(200, json={
        "choices": [{"finish_reason": "stop", "message": {"role": "assistant", "content": "done", "images": [
            {"type": "image_url", "image_url": {"url": data_url(out_png)}}]}}],
        "usage": {"prompt_tokens": 1500, "completion_tokens": 1290, "cost": 0.039},
    })  # fmt: skip
    image = load_image(png_bytes((400, 300)))
    res = openrouter.run_image_model(image, "en", "google/gemini-2.5-flash-image")

    method, path, _, headers, body = seen[0]
    assert (method, path) == ("POST", "/api/v1/chat/completions")
    assert headers["authorization"] == "Bearer or-test-key-123456"
    assert body["model"] == "google/gemini-2.5-flash-image"
    assert body["modalities"] == ["image", "text"]
    assert body["image_config"] == {"aspect_ratio": "4:3"}
    parts = body["messages"][0]["content"]
    assert parts[0]["type"] == "text"
    assert parts[0]["text"] == openrouter.build_prompt("en")
    assert "English" in parts[0]["text"] and "{language}" not in parts[0]["text"]
    assert parts[1]["type"] == "image_url" and parts[1]["image_url"]["url"].startswith("data:image/jpeg;base64,")

    assert res["api_used"] == "chat" and res["size"] == [100, 75]
    assert res["usage"]["cost"] == 0.039
    assert Image.open(io.BytesIO(res["png"])).size == (100, 75)


def test_non_png_output_is_converted_to_png(router):
    _, state = router
    buf = io.BytesIO()
    Image.new("RGB", (20, 20), (1, 2, 3)).save(buf, "JPEG")
    state["handler"] = lambda req, body: httpx2.Response(200, json={"choices": [{"message": {"images": [
        {"image_url": {"url": data_url(buf.getvalue(), "image/jpeg")}}]}}]})  # fmt: skip
    res = openrouter.run_image_model(load_image(png_bytes()), "ja", "m/x")
    assert Image.open(io.BytesIO(res["png"])).format == "PNG"


def test_auto_mode_falls_back_to_the_images_api_when_the_chat_route_is_not_for_this_model(router):
    seen, state = router
    out_png = png_bytes((50, 50))

    def handler(req, body):
        if req.url.path.endswith("/chat/completions"):
            return httpx2.Response(404, json={"error": {"message": "No endpoints found that support image output"}})
        return httpx2.Response(200, json={"created": 1, "data": [{"b64_json": base64.b64encode(out_png).decode(), "media_type": "image/png"}],
                                          "usage": {"prompt_tokens": 0, "completion_tokens": 4175, "total_tokens": 4175, "cost": 0.04}})  # fmt: skip

    state["handler"] = handler
    res = openrouter.run_image_model(load_image(png_bytes((300, 400))), "zh-TW", "bytedance-seed/seedream-4.5")
    paths = [s[1] for s in seen]
    assert paths == [
        "/api/v1/chat/completions",
        "/api/v1/chat/completions",  # retried once without the optional aspect ratio
        "/api/v1/images",
    ]
    body = seen[-1][4]
    assert body["model"] == "bytedance-seed/seedream-4.5"
    assert "Traditional Chinese (Taiwan)" in body["prompt"]
    assert body["input_references"][0]["type"] == "image_url"
    assert body["input_references"][0]["image_url"]["url"].startswith("data:image/jpeg;base64,")
    assert body["aspect_ratio"] == "3:4"
    assert res["api_used"] == "images" and res["usage"]["cost"] == 0.04
    assert len(res["attempts"]) == 2


def test_aspect_ratio_is_dropped_on_retry_when_the_provider_rejects_it(router):
    seen, state = router
    out_png = png_bytes((10, 10))

    def handler(req, body):
        if "image_config" in body:
            return httpx2.Response(400, json={"error": {"message": "unknown parameter image_config"}})
        return httpx2.Response(200, json={"choices": [{"message": {"images": [{"image_url": {"url": data_url(out_png)}}]}}]})

    state["handler"] = handler
    res = openrouter.run_image_model(load_image(png_bytes()), "en", "m/x")
    assert res["api_used"] == "chat (no aspect ratio)"
    assert len(seen) == 2


def test_chat_only_setting_does_not_try_the_images_route(router, monkeypatch):
    seen, state = router
    monkeypatch.setenv("OPENROUTER_IMAGE_API", "chat")
    state["handler"] = lambda req, body: httpx2.Response(404, json={"error": {"message": "nope"}})
    with pytest.raises(ModeError, match="no OpenRouter route"):
        openrouter.run_image_model(load_image(png_bytes()), "en", "m/x")
    assert {s[1] for s in seen} == {"/api/v1/chat/completions"}


def test_text_only_answer_is_reported_with_what_the_model_said(router):
    _, state = router
    state["handler"] = lambda req, body: httpx2.Response(200, json={"choices": [
        {"finish_reason": "stop", "message": {"content": "I cannot edit this image."}}]})  # fmt: skip
    with pytest.raises(ModeError, match="I cannot edit this image"):
        openrouter.run_image_model(load_image(png_bytes()), "en", "m/x")


@pytest.mark.parametrize("status", [401, 402, 429, 500])
def test_auth_billing_and_server_errors_are_not_masked_by_route_fallbacks(router, status):
    seen, state = router
    state["handler"] = lambda req, body: httpx2.Response(status, json={"error": {"message": f"err {status}"}})
    with pytest.raises(ModeError, match=f"HTTP {status}"):
        openrouter.run_image_model(load_image(png_bytes()), "en", "m/x")
    assert len(seen) == 1


def test_missing_key_fails_fast(router, monkeypatch):
    seen, _ = router
    monkeypatch.delenv("OPENROUTER_API_KEY")
    with pytest.raises(ModeError, match="OPENROUTER_API_KEY"):
        openrouter.run_image_model(load_image(png_bytes()), "en", "m/x")
    assert seen == []


def test_the_production_variable_name_works_as_an_alias(router, monkeypatch):
    seen, state = router
    monkeypatch.delenv("OPENROUTER_API_KEY")
    monkeypatch.setenv("OPEN_ROUTE_API_KEY", "prod-key-abcdef123")
    monkeypatch.setenv("OPEN_ROUTE_HTTP_REFERER", "https://example.test/lab")
    out_png = png_bytes((10, 10))
    state["handler"] = lambda req, body: httpx2.Response(
        200, json={"choices": [{"message": {"images": [{"image_url": {"url": data_url(out_png)}}]}}]}
    )
    openrouter.run_image_model(load_image(png_bytes()), "en", "m/x")
    headers = seen[0][3]
    assert headers["authorization"] == "Bearer prod-key-abcdef123"
    assert headers["http-referer"] == "https://example.test/lab"
    assert headers["x-title"] == "LineBotTranslator overlay-lab"


def test_nearest_aspect_ratio():
    assert openrouter.nearest_aspect_ratio(1000, 1000) == "1:1"
    assert openrouter.nearest_aspect_ratio(1080, 1920) == "9:16"
    assert openrouter.nearest_aspect_ratio(1600, 900) == "16:9"
    assert openrouter.nearest_aspect_ratio(800, 1000) == "4:5"
    assert openrouter.nearest_aspect_ratio(3000, 100) == "21:9"


def test_extractors_accept_https_urls_and_inline_content_parts():
    out_png = png_bytes((8, 8))
    transport = httpx2.MockTransport(lambda req: httpx2.Response(200, content=out_png))
    with httpx2.Client(transport=transport) as client:
        body = {"choices": [{"message": {"content": [
            {"type": "text", "text": "here"},
            {"type": "image_url", "image_url": {"url": "https://cdn.example/x.png"}},
        ]}}]}  # fmt: skip
        raw, said = openrouter.extract_chat_image(body, client)
        assert raw == out_png and said == "here"
    with pytest.raises(ModeError, match="has no choices"):
        openrouter.extract_chat_image({"choices": []})
    with pytest.raises(ModeError, match="credits"):
        openrouter.extract_chat_image({"error": {"message": "out of credits"}})


# ---------------------------------------------------------------- round 3: prompt v2, routes, size, retry


def test_prompt_v2_has_the_hard_rules():
    prompt = openrouter.build_prompt("ja")
    assert "{language}" not in prompt and prompt.count("Japanese") >= 3
    for rule in (
        "Translate EVERY piece of text",
        "every line of multi-line titles, headings, table cells, labels, captions, footers and small print",
        "brand name, a URL, an e-mail address or already in Japanese",
        "NEVER change",
        "digits, numbers, prices, dates, times, phone numbers or handwritten marks",
        "pixel-for-pixel",
        "layout, positions, sizes, fonts, colours, background, illustrations and photos",
        "dimensions and aspect ratio",
        "Output the edited image only",
    ):
        assert rule in prompt, rule


def test_readme_shows_the_prompt_that_the_code_uses():
    from pathlib import Path

    readme = Path(__file__).resolve().parents[1] / "README.md"
    if not readme.exists():  # the container image does not carry the README
        pytest.skip("README.md not available")
    text = readme.read_text(encoding="utf-8")
    assert openrouter.PROMPT_TEMPLATE in text
    assert openrouter.RETRY_TEMPLATE in text


@pytest.mark.parametrize(
    "model, expected",
    [
        ("google/gemini-3-pro-image", "images"),
        ("google/gemini-3-pro-image-preview", "images"),
        ("google/gemini-3.1-flash-image-preview", "images"),
        ("some-vendor/nano-banana-pro", "images"),
        ("google/gemini-2.5-flash-image", "chat"),
        ("openai/gpt-5-image", "chat"),
        ("bytedance-seed/seedream-4.5", "chat"),
    ],
)
def test_preferred_route_per_model(model, expected):
    assert openrouter.preferred_route(model) == expected


def test_route_order_per_model_and_the_forcing_setting():
    assert openrouter.route_order("google/gemini-3-pro-image") == ["images", "chat"]
    assert openrouter.route_order("google/gemini-2.5-flash-image") == ["chat", "images"]
    assert openrouter.route_order("google/gemini-3-pro-image", "chat") == ["chat"]
    assert openrouter.route_order("google/gemini-2.5-flash-image", "images") == ["images"]


def test_model_list_carries_the_preferred_route_per_model(router):
    _, state = router
    state["handler"] = lambda req, body: httpx2.Response(200, json={"data": [
        {"id": "google/gemini-3-pro-image-preview", "architecture": {"input_modalities": ["image"], "output_modalities": ["image"]}},
        {"id": "a/plain", "architecture": {"input_modalities": ["image"], "output_modalities": ["image"]}},
    ]})  # fmt: skip
    details = openrouter.list_image_models()["details"]
    assert details["google/gemini-3-pro-image-preview"]["preferred_route"] == "images"
    assert details["a/plain"]["preferred_route"] == "chat"
    assert details["google/gemini-2.5-flash-image"]["preferred_route"] == "chat"  # the default, not in the list


def images_body(out_png):
    return {"created": 1, "data": [{"b64_json": base64.b64encode(out_png).decode(), "media_type": "image/png"}],
            "usage": {"cost": 0.12}}  # fmt: skip


def test_gemini_3_goes_to_the_images_route_first_and_falls_back_to_chat(router):
    seen, state = router
    out_png = png_bytes((50, 50))

    def handler(req, body):
        if req.url.path.endswith("/images"):
            return httpx2.Response(502, json={"error": {"message": "generation failed"}})
        return httpx2.Response(200, json={"choices": [{"message": {"images": [{"image_url": {"url": data_url(out_png)}}]}}]})

    state["handler"] = handler
    res = openrouter.run_image_model(load_image(png_bytes((400, 300))), "en", "google/gemini-3-pro-image-preview")
    assert [s[1] for s in seen] == ["/api/v1/images", "/api/v1/chat/completions"]
    assert res["api_used"] == "chat" and res["route_preference"] == "images"
    assert "HTTP 502" in res["attempts"][0]


def test_other_models_keep_the_chat_first_order(router):
    seen, state = router
    out_png = png_bytes((50, 50))
    state["handler"] = lambda req, body: (
        httpx2.Response(404, json={"error": {"message": "no"}}) if req.url.path.endswith("/chat/completions")
        else httpx2.Response(200, json=images_body(out_png))
    )
    openrouter.run_image_model(load_image(png_bytes()), "en", "google/gemini-2.5-flash-image")
    assert [s[1] for s in seen][:2] == ["/api/v1/chat/completions", "/api/v1/chat/completions"]
    assert seen[-1][1] == "/api/v1/images"


def test_the_forcing_setting_beats_the_per_model_route(router, monkeypatch):
    seen, state = router
    monkeypatch.setenv("OPENROUTER_IMAGE_API", "chat")
    state["handler"] = lambda req, body: httpx2.Response(404, json={"error": {"message": "nope"}})
    with pytest.raises(ModeError):
        openrouter.run_image_model(load_image(png_bytes()), "en", "google/gemini-3-pro-image")
    assert {s[1] for s in seen} == {"/api/v1/chat/completions"}


def test_resolution_is_sent_on_the_images_route_only(router):
    seen, state = router
    out_png = png_bytes((64, 48))
    state["handler"] = lambda req, body: httpx2.Response(200, json=images_body(out_png))
    res = openrouter.run_image_model(load_image(png_bytes((400, 300))), "en", "google/gemini-3-pro-image", resolution="2K")
    body = seen[0][4]
    assert seen[0][1] == "/api/v1/images"
    assert body["resolution"] == "2K" and body["aspect_ratio"] == "4:3"
    assert "size" not in body
    assert res["resolution_requested"] == "2K" and res["resolution_applied"] is True and res["api_used"] == "images"


def test_default_size_sends_no_resolution_parameter(router):
    seen, state = router
    state["handler"] = lambda req, body: httpx2.Response(200, json=images_body(png_bytes()))
    res = openrouter.run_image_model(load_image(png_bytes()), "en", "google/gemini-3-pro-image")
    assert "resolution" not in seen[0][4] and res["resolution_applied"] is False


def test_chat_route_never_carries_the_size_option_and_says_so(router):
    seen, state = router
    out_png = png_bytes((10, 10))
    state["handler"] = lambda req, body: httpx2.Response(200, json={"choices": [{"message": {"images": [{"image_url": {"url": data_url(out_png)}}]}}]})
    res = openrouter.run_image_model(load_image(png_bytes()), "en", "google/gemini-2.5-flash-image", resolution="4K")
    assert seen[0][1] == "/api/v1/chat/completions"
    assert "resolution" not in json.dumps(seen[0][4]) and "4K" not in json.dumps(seen[0][4])
    assert res["resolution_requested"] == "4K" and res["resolution_applied"] is False


def test_a_provider_that_rejects_resolution_gets_the_request_again_without_it(router):
    seen, state = router
    out_png = png_bytes((10, 10))

    def handler(req, body):
        if "resolution" in body:
            return httpx2.Response(400, json={"error": {"message": "unsupported parameter: resolution"}})
        return httpx2.Response(200, json=images_body(out_png))

    state["handler"] = handler
    res = openrouter.run_image_model(load_image(png_bytes()), "en", "google/gemini-3-pro-image", resolution="2K")
    assert [("resolution" in s[4], "aspect_ratio" in s[4]) for s in seen] == [(True, True), (False, True)]
    assert res["api_used"] == "images (no resolution)" and res["resolution_applied"] is False
    assert "unsupported parameter" in res["attempts"][0]


def test_aspect_ratio_and_resolution_are_both_dropped_in_turn(router):
    seen, state = router
    out_png = png_bytes((10, 10))
    state["handler"] = lambda req, body: (
        httpx2.Response(422, json={"error": {"message": "bad"}}) if len(body) > 3
        else httpx2.Response(200, json=images_body(out_png))
    )  # model, prompt, input_references only -> accepted
    res = openrouter.run_image_model(load_image(png_bytes()), "en", "google/gemini-3-pro-image", resolution="1K")
    assert len(seen) == 3 and res["api_used"] == "images (no resolution, no aspect ratio)"


def test_a_custom_prompt_replaces_the_default_one(router):
    seen, state = router
    state["handler"] = lambda req, body: httpx2.Response(200, json=images_body(png_bytes()))
    res = openrouter.run_image_model(load_image(png_bytes()), "en", "google/gemini-3-pro-image", prompt="custom prompt")
    assert seen[0][4]["prompt"] == "custom prompt" and res["prompt"] == "custom prompt"


def test_retry_prompt_is_the_prompt_plus_a_paragraph_naming_what_went_wrong():
    verification = {
        "missing_digit_runs": ["77446716", "1230"],
        "missing_digit_details": [
            {"run": "77446716", "as_written": "7744-6716"},
            {"run": "1230", "as_written": "12:30"},
        ],
        "leftover_ok": False,
        "leftover_words": [f"word{i}" for i in range(15)],
    }
    prompt = openrouter.build_retry_prompt("en", verification)
    base = openrouter.build_prompt("en")
    assert prompt.startswith(base + "\n\n")
    extra = prompt[len(base) + 2:]
    assert extra.startswith("Your previous attempt")
    assert "7744-6716, 12:30" in extra  # the digits as they are written in the source
    assert "word0, word1, word2, word3, word4, word5, word6, word7, word8, word9" in extra
    assert "word10" not in extra  # at most 10 leftover words
    assert "English" in extra


def test_retry_prompt_names_what_the_checks_found_and_nothing_else():
    digits_only = openrouter.build_retry_prompt("ja", {"missing_digit_runs": ["1234"], "leftover_words": []})
    assert "1234" in digits_only and "still in the source language" not in digits_only
    words_only = openrouter.build_retry_prompt("ja", {"missing_digit_runs": [], "leftover_ok": False, "leftover_words": ["こんにちは"]})
    assert "こんにちは" in words_only and "digit sequences" not in words_only and "Japanese" in words_only
    # a digits failure that also left a few words behind (under the threshold) names them as well
    both = openrouter.build_retry_prompt("ja", {"missing_digit_runs": ["1234"], "leftover_ok": True, "leftover_words": ["x1"]})
    assert "1234" in both and "x1" in both
    # nothing to name: the plain prompt
    assert openrouter.build_retry_prompt("ja", {"missing_digit_runs": [], "leftover_ok": True}) == openrouter.build_prompt("ja")


def test_retry_prompt_caps_the_digit_runs_at_twenty():
    runs = [f"{i:04d}" for i in range(1000, 1030)]
    prompt = openrouter.build_retry_prompt("en", {"missing_digit_runs": runs, "leftover_ok": True})
    assert "1019" in prompt and "1020" not in prompt
