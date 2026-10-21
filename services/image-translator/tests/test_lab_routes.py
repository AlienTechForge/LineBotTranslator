"""The lab under /lab (behind LAB_TOKEN), and its old root paths for one release."""

import io
import re
import time
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from PIL import Image

from app.main import create_app

TEMPLATE = Path(__file__).resolve().parents[1] / "app" / "templates" / "index.html"


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


def test_the_page_is_served_at_lab_with_the_language_list_of_the_config(make_client):
    r = make_client().get("/lab/")
    assert r.status_code == 200 and "LAB_MOCK=1" in r.text and "Mode A" in r.text
    assert "default-src 'self'" in r.headers["content-security-policy"]
    for code in ("en", "zh-TW", "zh-CN", "ja", "ko", "vi", "th", "fr", "ar", "uk"):
        assert f'value="{code}"' in r.text, code
    assert r.text.count('<option value="zh-TW"') == 1


def test_lab_without_a_slash_goes_to_the_page_with_its_token(make_client):
    client = make_client(LAB_TOKEN="s3cret")
    r = client.get("/lab?token=s3cret", follow_redirects=False)
    assert r.status_code in (307, 308) and r.headers["location"].endswith("/lab/?token=s3cret")
    assert client.get("/lab?token=s3cret").status_code == 200


def test_the_page_only_uses_urls_relative_to_itself(make_client):
    html = TEMPLATE.read_text(encoding="utf-8")
    fetches = re.findall(r"fetch\(url\('([^']+)'", html)
    assert fetches and all(not f.startswith("/") for f in fetches), fetches  # /lab/ + api/... = /lab/api/...
    assert "href=\"/" not in html and "src=\"/" not in html


def test_lab_token_gates_every_lab_route_new_and_old(make_client):
    client = make_client(LAB_TOKEN="s3cret-token")
    paths = (
        ("get", "/lab/"), ("get", "/lab/api/image-models"), ("get", "/lab/api/mock-image"),
        ("get", "/lab/api/jobs/0123456789abcdef"), ("get", "/lab/files/0123456789abcdef/A.png"),
        ("post", "/lab/api/jobs"), ("post", "/lab/api/batches"), ("get", "/lab/api/batches/0123456789abcdef"),
        ("get", "/lab/api/batches/0123456789abcdef/manifest"),
        ("get", "/"), ("get", "/api/image-models"), ("post", "/api/jobs"), ("get", "/files/0123456789abcdef/A.png"),
    )  # fmt: skip
    for method, path in paths:
        assert getattr(client, method)(path).status_code == 401, path
    assert client.get("/lab/?token=wrong").status_code == 401
    assert client.get("/lab/", headers={"X-Lab-Token": "wrong"}).status_code == 401
    assert client.get("/lab/?token=s3cret-token").status_code == 200
    assert client.get("/lab/", headers={"X-Lab-Token": "s3cret-token"}).status_code == 200
    assert client.get("/lab/api/image-models?token=s3cret-token").status_code == 200
    assert client.get("/healthz").status_code == 200  # the health check is not part of the lab


def test_the_root_goes_to_the_lab_and_keeps_the_query_string(make_client, monkeypatch):
    client = make_client(LAB_TOKEN="s3cret")
    r = client.get("/?token=s3cret", follow_redirects=False)
    assert r.status_code == 307 and r.headers["location"] == "/lab/?token=s3cret" and r.headers["deprecation"] == "true"
    assert client.get("/", follow_redirects=False).status_code == 401
    monkeypatch.delenv("LAB_TOKEN")
    plain = make_client().get("/", follow_redirects=False)
    assert plain.status_code == 307 and plain.headers["location"] == "/lab/"
    assert make_client().get("/").status_code == 200  # followed: the page


def _wait(client, prefix, job_id):
    deadline = time.time() + 30
    while time.time() < deadline:
        job = client.get(f"{prefix}/api/jobs/{job_id}").json()
        if job["status"] == "done":
            return job
        time.sleep(0.05)
    raise AssertionError("job did not finish")


def test_a_job_runs_end_to_end_under_lab_and_its_files_are_served_there(make_client):
    client = make_client()
    r = client.post("/lab/api/jobs", files={"file": ("a.png", png_bytes(), "image/png")}, data=form(mode_b="0", verify="1"))
    assert r.status_code == 200
    job = _wait(client, "/lab", r.json()["job_id"])
    assert job["mode_a"]["status"] == "done" and job["image"]["url"].startswith("files/")  # relative: /lab/ + files/...
    shown = client.get("/lab/" + job["image"]["url"])
    assert shown.status_code == 200 and shown.headers["content-type"] == "image/png"
    assert client.get("/lab/" + f"files/{job['job_id']}/{job['mode_a']['output']}").status_code == 200
    # Mode A's judge ignores rows (round 7): the verdict has no rows entry that can fail
    assert job["mode_a"]["verification"]["checks"]["rows"]["ok"] is True
    assert job["mode_a"]["verification"]["structure_checks"] is False


def test_the_old_root_paths_still_run_a_job_and_say_so(make_client):
    client = make_client()
    r = client.post("/api/jobs", files={"file": ("a.png", png_bytes(), "image/png")}, data=form(mode_b="0"))
    assert r.status_code == 200 and r.headers["deprecation"] == "true"
    job = _wait(client, "", r.json()["job_id"])
    ok = client.get("/" + job["image"]["url"])
    assert ok.status_code == 200 and ok.headers["deprecation"] == "true"
    batch = client.post("/lab/api/batches", files=[("files", ("a.png", png_bytes(), "image/png"))], data=form(mode_b="0"))
    assert batch.status_code == 200 and set(batch.json()) == {"batch_id", "job_ids"}
    assert client.get(f"/api/batches/{batch.json()['batch_id']}").status_code == 200  # the alias sees the same store


def test_lab_does_not_need_the_service_token_and_v1_does_not_need_the_lab_token(make_client):
    client = make_client(SERVICE_TOKEN="svc", LAB_TOKEN="lab")
    assert client.get("/lab/?token=lab").status_code == 200
    assert client.get("/v1/translations/abc", headers={"X-Lab-Token": "lab", "X-Service-Token": "svc"}).status_code == 404  # authorised, just no such id
    assert client.get("/v1/translations/abc", headers={"X-Lab-Token": "lab"}).status_code == 401


def test_the_lab_page_still_works_with_the_wide_language_table(make_client):
    client = make_client()
    ok = client.post("/lab/api/jobs", files={"file": ("a.png", png_bytes(), "image/png")}, data=form(target_lang="th", mode_b="0"))
    assert ok.status_code == 200
    bad = client.post("/lab/api/jobs", files={"file": ("a.png", png_bytes(), "image/png")}, data=form(target_lang="xx"))
    assert bad.status_code == 400  # the lab keeps its dropdown: only the table's codes


def test_in_a_lab_job_rows_fail_mode_b_but_not_mode_a(make_client, monkeypatch):
    from scenario import judge_record

    from app import judge

    noise = judge_record(0.95, rows=[("misaligned_marks", "marks next to the wrong dish")])
    monkeypatch.setattr(judge, "judge_output", lambda *a, **k: dict(noise))
    client = make_client()
    r = client.post(
        "/lab/api/jobs", files={"file": ("a.png", png_bytes((640, 480)), "image/png")},
        data=form(mode_b="1", verify="1", image_models="mock/image-model"),
    )  # fmt: skip
    job = _wait(client, "/lab", r.json()["job_id"])
    a, b = job["mode_a"], job["mode_b"]["results"][0]
    assert a["verdict"] == "pass" and a["verification"]["checks"]["rows"] == {"ok": True, "count": 0, "ignored": 1}
    assert b["verdict"] == "rows" and b["verification"]["checks"]["rows"] == {"ok": False, "count": 1}
    assert a["verification"]["structure_warnings"][0]["type"] == "misaligned_marks"
