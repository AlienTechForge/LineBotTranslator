"""HTTP surface: token gate, upload checks, file serving, model list endpoint."""

import io

import pytest
from fastapi.testclient import TestClient
from PIL import Image

from app.main import create_app


def png_bytes(size=(80, 60)) -> bytes:
    buf = io.BytesIO()
    Image.new("RGB", size, (250, 245, 230)).save(buf, "PNG")
    return buf.getvalue()


@pytest.fixture
def make_client(monkeypatch, tmp_path):
    def build(**env):
        monkeypatch.setenv("LAB_DATA_DIR", str(tmp_path / "data"))
        monkeypatch.setenv("LAB_MOCK", "1")
        for k, v in env.items():
            monkeypatch.setenv(k, v)
        return TestClient(create_app())

    return build


def form(**kw):
    data = {"target_lang": "en", "mode_a": "1", "mode_b": "1", "image_model": "", "verify": "0"}
    data.update(kw)
    return data


def test_index_page_renders_with_languages_and_mock_badge(make_client):
    client = make_client()
    r = client.get("/")
    assert r.status_code == 200
    html = r.text
    for code in ("en", "zh-TW", "zh-CN", "ja", "ko", "vi"):
        assert f'value="{code}"' in html
    assert "LAB_MOCK=1" in html and "Mode A" in html and "Mode B" in html
    assert 'rel="icon"' in html  # no /favicon.ico request, so no 404 in the browser console
    assert "default-src 'self'" in r.headers["content-security-policy"]
    assert r.headers["x-content-type-options"] == "nosniff"


def test_token_is_required_on_every_route_when_set(make_client):
    client = make_client(LAB_TOKEN="s3cret-token")
    for method, path in (
        ("get", "/"),
        ("get", "/api/image-models"),
        ("get", "/api/mock-image"),
        ("get", "/api/jobs/0123456789abcdef"),
        ("get", "/files/0123456789abcdef/A.png"),
        ("post", "/api/jobs"),
        ("post", "/api/batches"),
        ("get", "/api/batches/0123456789abcdef"),
        ("get", "/api/batches/0123456789abcdef/manifest"),
    ):
        assert getattr(client, method)(path).status_code == 401, path
    assert client.get("/?token=wrong").status_code == 401
    assert client.get("/", headers={"X-Lab-Token": "wrong"}).status_code == 401
    assert client.get("/?token=s3cret-token").status_code == 200
    assert client.get("/", headers={"X-Lab-Token": "s3cret-token"}).status_code == 200
    assert client.get("/api/image-models?token=s3cret-token").status_code == 200
    r = client.post("/api/jobs?token=s3cret-token", files={"file": ("a.png", png_bytes(), "image/png")}, data=form())
    assert r.status_code == 200
    r = client.post("/api/batches?token=s3cret-token", files=[("files", ("a.png", png_bytes(), "image/png"))], data=form())
    assert r.status_code == 200 and set(r.json()) == {"batch_id", "job_ids"}


def test_no_token_configured_means_open_access(make_client):
    assert make_client().get("/api/image-models").status_code == 200


def test_image_models_endpoint_in_mock_mode(make_client):
    body = make_client().get("/api/image-models").json()
    assert body["default"] == "google/gemini-3-pro-image"  # round 5: Pro is what the page preselects
    assert body["models"][0] == body["default"]


def test_non_image_content_types_are_rejected(make_client):
    client = make_client()
    r = client.post("/api/jobs", files={"file": ("a.txt", b"hello", "text/plain")}, data=form())
    assert r.status_code == 415
    r = client.post("/api/jobs", files={"file": ("a.gif", b"GIF89a", "image/gif")}, data=form())
    assert r.status_code == 415


def test_file_that_claims_to_be_an_image_but_is_not_is_rejected(make_client):
    r = make_client().post("/api/jobs", files={"file": ("a.png", b"definitely not a png", "image/png")}, data=form())
    assert r.status_code == 415 and "decodable" in r.json()["detail"]


def test_a_decodable_image_in_an_unsupported_format_is_rejected(make_client):
    buf = io.BytesIO()
    Image.new("RGB", (10, 10)).save(buf, "BMP")
    r = make_client().post("/api/jobs", files={"file": ("a.png", buf.getvalue(), "image/png")}, data=form())
    assert r.status_code == 415 and "unsupported" in r.json()["detail"]


def test_uploads_over_20_mb_are_refused(make_client):
    big = b"\x89PNG\r\n\x1a\n" + b"0" * (20 * 1024 * 1024 + 10)
    r = make_client().post("/api/jobs", files={"file": ("a.png", big, "image/png")}, data=form())
    assert r.status_code == 413


def test_parameter_validation(make_client):
    client = make_client()
    files = {"file": ("a.png", png_bytes(), "image/png")}
    assert client.post("/api/jobs", files=files, data=form(target_lang="fr")).status_code == 400
    assert client.post("/api/jobs", files=files, data=form(mode_a="0", mode_b="0")).status_code == 400
    assert client.post("/api/jobs", files=files, data=form(image_model="bad model!")).status_code == 400
    assert client.post("/api/jobs", data=form()).status_code == 422  # no file


def test_job_and_file_lookups_reject_bad_ids_and_traversal(make_client, tmp_path):
    client = make_client()
    assert client.get("/api/jobs/not-a-job").status_code == 404
    assert client.get("/api/jobs/0123456789abcdef").status_code == 404
    assert client.get("/files/0123456789abcdef/A.png").status_code == 404
    for evil in ("..%2f..%2fetc%2fpasswd", "..", "%2e%2e%2fsecret.png", ".hidden.png"):
        assert client.get(f"/files/0123456789abcdef/{evil}").status_code in (404, 422), evil
    r = client.post("/api/jobs", files={"file": ("a.png", png_bytes(), "image/png")}, data=form(mode_b="0"))
    job_id = r.json()["job_id"]
    (tmp_path / "data" / "secret.png").write_bytes(png_bytes())
    assert client.get(f"/files/{job_id}/..%2fsecret.png").status_code == 404
    assert client.get(f"/files/{job_id}/original.png").status_code == 200
    assert client.get(f"/files/{job_id}/notes.txt").status_code == 404
    assert client.get(f"/files/{job_id}/original.png?dl=1").headers["content-disposition"].startswith("attachment")


def test_mock_image_is_only_served_in_mock_mode(make_client):
    assert make_client().get("/api/mock-image").headers["content-type"] == "image/png"
    assert make_client(LAB_MOCK="0").get("/api/mock-image").status_code == 404


def test_the_page_has_the_per_file_language_rows_and_sends_target_langs_in_file_order(make_client):
    page = make_client().get("/").text
    assert 'id="file-langs"' in page and 'id="file-lang-list"' in page and "每張圖的目標語言" in page
    assert "fd.append('target_langs', r.select.value)" in page  # one value per file, appended after `target_lang`
    assert page.index("fd.append('target_lang',") < page.index("fd.append('target_langs'") < page.index("fd.append('mode_a'")
    assert "['語言', '']" in page and "r.target_lang" in page  # the summary table has the column
    assert "'覆蓋補翻'" in page and "補翻 ${cov.added_words} 字" in page  # the coverage line of Mode A
    assert page.count('<option value="zh-TW"') == 1  # the language list of the page is the one of the config
