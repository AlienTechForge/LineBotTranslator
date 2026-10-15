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
    assert parts[0]["text"] == (
        "Replace every piece of text in this image with its translation into English. Keep the layout, "
        "positions, sizes, fonts, colours, background and all graphics exactly as they are. Do not add, "
        "remove or move any element. Output the edited image only."
    )
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
