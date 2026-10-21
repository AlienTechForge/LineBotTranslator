"""`python -m app` as a real process over real HTTP in mock mode, and how it reads BIND_HOST / PORT."""

import io
import os
import runpy
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

import httpx2
from PIL import Image

from app import config

ROOT = Path(__file__).resolve().parents[1]
TOKEN = "process-service-token"


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def test_bind_host_defaults_to_loopback_and_port_to_4041():
    settings = config.get_settings()
    assert settings.bind_host == "127.0.0.1" and settings.port == 4041


def test_bind_host_and_port_come_from_the_environment_and_lab_port_is_still_honoured(monkeypatch):
    monkeypatch.setenv("BIND_HOST", "0.0.0.0")
    monkeypatch.setenv("PORT", "5055")
    monkeypatch.setenv("LAB_PORT", "6066")
    settings = config.get_settings()
    assert (settings.bind_host, settings.port) == ("0.0.0.0", 5055)  # PORT wins
    monkeypatch.delenv("PORT")
    assert config.get_settings().port == 6066
    for bad in ("abc", "0", "70000", "-1"):
        monkeypatch.setenv("LAB_PORT", bad)
        assert config.get_settings().port == 4041, bad


def test_main_starts_uvicorn_with_the_settings_and_no_access_log(monkeypatch):
    import uvicorn

    seen = {}
    monkeypatch.setattr(uvicorn, "run", lambda app, **kw: seen.update(app=app, **kw))
    monkeypatch.setenv("PORT", "4999")
    runpy.run_module("app", run_name="__main__")
    assert seen["app"] == "app.main:app" and seen["host"] == "127.0.0.1" and seen["port"] == 4999
    assert seen["access_log"] is False  # URLs may carry ?token=
    monkeypatch.setenv("BIND_HOST", "0.0.0.0")
    runpy.run_module("app", run_name="__main__")
    assert seen["host"] == "0.0.0.0"


def png() -> bytes:
    buf = io.BytesIO()
    Image.new("RGB", (640, 480), (245, 239, 224)).save(buf, "PNG")
    return buf.getvalue()


def test_the_server_process_answers_over_real_http_in_mock_mode(tmp_path):
    port = free_port()
    env = {
        **os.environ, "LAB_MOCK": "1", "PORT": str(port), "SERVICE_TOKEN": TOKEN, "LAB_DATA_DIR": str(tmp_path / "data"),
        "LAB_TOKEN": "", "PYTHONUNBUFFERED": "1",
    }  # fmt: skip
    for name in ("BIND_HOST", "LAB_PORT"):
        env.pop(name, None)
    proc = subprocess.Popen([sys.executable, "-m", "app"], cwd=ROOT, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    base = f"http://127.0.0.1:{port}"
    try:
        deadline = time.time() + 60
        while True:
            try:
                urllib.request.urlopen(base + "/healthz", timeout=2).read()
                break
            except (urllib.error.URLError, ConnectionError, OSError):
                assert proc.poll() is None, "the server exited"
                assert time.time() < deadline, "the server did not start"
                time.sleep(0.2)
        with httpx2.Client(timeout=60) as client:
            health = client.get(base + "/healthz")
            assert health.status_code == 200 and health.json()["mock"] is True and health.json()["status"] == "ok"
            assert client.post(base + "/v1/translations", files={"image": ("a.png", png(), "image/png")}, data={"target_lang": "zh-TW"}).status_code == 401
            headers = {"X-Service-Token": TOKEN}
            answers = {}
            for name in ("a_then_b", "both"):
                r = client.post(
                    base + "/v1/translations", headers=headers, files={"image": ("a.png", png(), "image/png")},
                    data={"target_lang": "zh-TW", "policy": name, "request_id": f"proc-{name}"},
                )  # fmt: skip
                assert r.status_code == 200, r.text
                answers[name] = r.json()
                assert (answers[name]["status"], answers[name]["tier"], answers[name]["reason"]) == ("image", "clean", "a_pass")
            assert [a["mode"] for a in answers["a_then_b"]["attempts"]] == ["A"]
            assert [a["mode"] for a in answers["both"]["attempts"]] == ["A", "B"]
            shown = client.get(base + answers["both"]["image"]["path"], headers=headers)
            assert shown.status_code == 200 and shown.headers["content-type"] == "image/png"
            assert len(shown.content) == answers["both"]["image"]["bytes"] and shown.content[:8] == b"\x89PNG\r\n\x1a\n"
            page = client.get(base + "/lab/")
            assert page.status_code == 200 and "LAB_MOCK=1" in page.text
            assert client.get(base + "/", follow_redirects=True).status_code == 200  # the old root goes to /lab/
            assert (tmp_path / "data" / "budget.json").is_file()
    finally:
        proc.terminate()
        try:
            output = proc.communicate(timeout=20)[0].decode("utf-8", "replace")
        except subprocess.TimeoutExpired:
            proc.kill()
            output = proc.communicate()[0].decode("utf-8", "replace")
    assert "stage=v1 request=proc-both status=image" in output  # the access log is off, the stage lines are on
    for needle in ("TONKATSU", "Pork Cutlet", TOKEN, "GET /healthz", "POST /v1/translations"):
        assert needle not in output, needle


def test_a_three_image_batch_with_modes_a_b_and_c_runs_over_real_http_and_publishes_to_the_fake(tmp_path):
    port = free_port()
    lab_token = "process-lab-token"
    env = {
        **os.environ, "LAB_MOCK": "1", "PORT": str(port), "LAB_DATA_DIR": str(tmp_path / "data"), "LAB_TOKEN": lab_token,
        "SERVICE_TOKEN": "", "PYTHONUNBUFFERED": "1",
    }  # fmt: skip
    for name in ("BIND_HOST", "LAB_PORT"):
        env.pop(name, None)
    proc = subprocess.Popen([sys.executable, "-m", "app"], cwd=ROOT, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    base = f"http://127.0.0.1:{port}"
    try:
        deadline = time.time() + 60
        while True:
            try:
                urllib.request.urlopen(base + "/healthz", timeout=2).read()
                break
            except (urllib.error.URLError, ConnectionError, OSError):
                assert proc.poll() is None, "the server exited"
                assert time.time() < deadline, "the server did not start"
                time.sleep(0.2)
        headers = {"X-Lab-Token": lab_token}
        with httpx2.Client(timeout=60) as client:
            files = [("files", (f"menu{i}.png", png(), "image/png")) for i in (1, 2, 3)]
            data = {"target_lang": "zh-TW", "mode_a": "1", "mode_b": "1", "mode_c": "1", "image_models": "mock/image-model", "verify": "1"}
            r = client.post(base + "/lab/api/batches", headers=headers, files=files, data=data)
            assert r.status_code == 200, r.text
            batch_id = r.json()["batch_id"]
            deadline = time.time() + 120
            while True:
                doc = client.get(base + f"/lab/api/batches/{batch_id}", headers=headers).json()
                if doc["status"] == "done" and doc["publish"]["status"] in ("done", "skipped", "error"):
                    break
                assert time.time() < deadline, "the batch did not finish"
                time.sleep(0.2)
            assert doc["publish"]["status"] == "done" and doc["publish"]["mock"] is True  # the in-memory fake took the manifest
            assert sorted((row["image_index"], row["mode"]) for row in doc["summary"]) == [(i, m) for i in (1, 2, 3) for m in "ABC"]
            assert all(row["status"] == "done" and row["verdict"] == "pass" for row in doc["summary"])
            assert all(job["mode_c"]["status"] == "done" and job["publish"]["status"] == "done" for job in doc["jobs"])
            shown = client.get(base + f"/lab/files/{doc['jobs'][0]['job_id']}/C_a1.png", headers=headers)
            assert shown.status_code == 200 and shown.content[:8] == b"\x89PNG\r\n\x1a\n"
    finally:
        proc.terminate()
        try:
            output = proc.communicate(timeout=20)[0].decode("utf-8", "replace")
        except subprocess.TimeoutExpired:
            proc.kill()
            output = proc.communicate()[0].decode("utf-8", "replace")
    assert "stage=segment" in output and "stage=mode-c-done" in output and "stage=claude-text" in output
    for needle in ("TONKATSU", "LUNCH", "NT$120", "紅燒肉", "[zh-TW]", lab_token, "X-Amz-Signature", "mock-minio"):
        assert needle not in output, needle  # no OCR text, no translation, no token, no link in the log
