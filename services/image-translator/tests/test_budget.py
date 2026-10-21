"""The monthly budget file: the month, the limits, atomic writes."""

import json
import threading
from datetime import datetime, timedelta, timezone

import pytest

from app import budget


@pytest.fixture(autouse=True)
def data_dir(monkeypatch, tmp_path):
    monkeypatch.setenv("LAB_DATA_DIR", str(tmp_path / "data"))
    return tmp_path / "data"


def at(text: str):
    """Make `budget.now` say this UTC moment."""
    return lambda: datetime.fromisoformat(text).replace(tzinfo=timezone.utc)


def test_a_fresh_state_is_zero_with_the_default_limits():
    assert budget.snapshot() == {
        "month": budget.current_month(), "openrouter_spent_usd": 0.0, "openrouter_budget_usd": 15.0,
        "anthropic_spent_usd": 0.0, "anthropic_budget_usd": 80.0,
    }  # fmt: skip
    assert not budget.path().exists()  # reading never creates the file


def test_the_file_holds_the_month_the_two_amounts_and_the_request_count(monkeypatch):
    monkeypatch.setattr(budget, "now", at("2026-10-05T03:00:00"))
    budget.add(openrouter_usd=0.25, anthropic_usd=1.5, requests=1)
    budget.add(openrouter_usd=0.125, anthropic_usd=0.5)
    assert json.loads(budget.path().read_text()) == {"month": "2026-10", "openrouter_usd": 0.375, "anthropic_usd": 2.0, "requests": 1}
    assert budget.snapshot()["anthropic_spent_usd"] == 2.0


def test_zero_costs_still_write_the_file(monkeypatch):
    budget.add(requests=1)
    assert json.loads(budget.path().read_text())["requests"] == 1


def test_bad_amounts_count_as_zero():
    budget.add(openrouter_usd=-1, anthropic_usd=float("nan"))
    budget.add(openrouter_usd=float("inf"), anthropic_usd="3")  # type: ignore[arg-type]
    budget.add(openrouter_usd=True, anthropic_usd=None)  # type: ignore[arg-type]
    state = budget.state()
    assert state["openrouter_usd"] == 0.0 and state["anthropic_usd"] == 0.0


def test_a_new_month_starts_from_zero(monkeypatch):
    monkeypatch.setattr(budget, "now", at("2026-10-31T10:00:00"))
    budget.add(openrouter_usd=14.0, anthropic_usd=70.0, requests=9)
    assert budget.state()["openrouter_usd"] == 14.0
    monkeypatch.setattr(budget, "now", at("2026-11-02T10:00:00"))
    assert budget.state() == {"month": "2026-11", "openrouter_usd": 0.0, "anthropic_usd": 0.0, "requests": 0}
    assert json.loads(budget.path().read_text())["month"] == "2026-10"  # a read does not rewrite the old month
    budget.add(openrouter_usd=0.5)
    assert json.loads(budget.path().read_text()) == {"month": "2026-11", "openrouter_usd": 0.5, "anthropic_usd": 0.0, "requests": 0}


def test_a_spent_out_budget_is_free_again_in_the_new_month(monkeypatch):
    monkeypatch.setenv("OPENROUTER_MONTHLY_BUDGET_USD", "1")
    monkeypatch.setattr(budget, "now", at("2026-10-10T00:00:00"))
    budget.add(openrouter_usd=1.0)
    assert not budget.allows_openrouter()
    monkeypatch.setattr(budget, "now", at("2026-11-10T00:00:00"))
    assert budget.allows_openrouter()


def test_the_month_is_the_calendar_month_in_taipei(monkeypatch):
    monkeypatch.setattr(budget, "now", at("2026-10-31T15:59:59"))  # 23:59:59 in Taipei
    assert budget.current_month() == "2026-10"
    monkeypatch.setattr(budget, "now", at("2026-10-31T16:00:00"))  # midnight in Taipei: already November there
    assert budget.current_month() == "2026-11"
    monkeypatch.setattr(budget, "now", lambda: datetime(2026, 12, 31, 20, 0, tzinfo=timezone(timedelta(hours=-5))))  # 01:00 on 1 January in Taipei
    assert budget.current_month() == "2027-01"


def test_the_default_clock_runs_in_taipei():
    assert budget.now().utcoffset() == timedelta(hours=8)


@pytest.mark.parametrize("spent, limit, allowed", [(0.0, 15.0, True), (14.999999, 15.0, True), (15.0, 15.0, False), (20.0, 15.0, False)])
def test_openrouter_is_allowed_while_spent_is_below_the_limit(monkeypatch, spent, limit, allowed):
    monkeypatch.setenv("OPENROUTER_MONTHLY_BUDGET_USD", str(limit))
    budget.add(openrouter_usd=spent)
    assert budget.allows_openrouter() is allowed
    assert budget.allows_anthropic() is True  # the other account is untouched


@pytest.mark.parametrize("spent, limit, allowed", [(0.0, 80.0, True), (79.9, 80.0, True), (80.0, 80.0, False), (95.0, 80.0, False)])
def test_anthropic_is_allowed_while_spent_is_below_the_limit(monkeypatch, spent, limit, allowed):
    monkeypatch.setenv("ANTHROPIC_MONTHLY_BUDGET_USD", str(limit))
    budget.add(anthropic_usd=spent)
    assert budget.allows_anthropic() is allowed
    assert budget.allows_openrouter() is True


def test_zero_means_unlimited(monkeypatch):
    monkeypatch.setenv("OPENROUTER_MONTHLY_BUDGET_USD", "0")
    monkeypatch.setenv("ANTHROPIC_MONTHLY_BUDGET_USD", "0")
    budget.add(openrouter_usd=10_000, anthropic_usd=10_000)
    assert budget.allows_openrouter() and budget.allows_anthropic()
    assert budget.snapshot()["openrouter_budget_usd"] == 0.0


@pytest.mark.parametrize("raw", ["", "abc", "-3", "nan", "inf"])
def test_a_bad_limit_falls_back_to_the_default(monkeypatch, raw):
    monkeypatch.setenv("OPENROUTER_MONTHLY_BUDGET_USD", raw)
    monkeypatch.setenv("ANTHROPIC_MONTHLY_BUDGET_USD", raw)
    assert budget.limits() == (15.0, 80.0)


def test_the_limits_come_from_the_environment(monkeypatch):
    monkeypatch.setenv("OPENROUTER_MONTHLY_BUDGET_USD", "3.5")
    monkeypatch.setenv("ANTHROPIC_MONTHLY_BUDGET_USD", "40")
    assert budget.limits() == (3.5, 40.0)
    assert budget.snapshot()["openrouter_budget_usd"] == 3.5 and budget.snapshot()["anthropic_budget_usd"] == 40.0


def test_the_file_is_replaced_atomically_and_leaves_nothing_behind(monkeypatch, data_dir):
    budget.add(openrouter_usd=1.0)
    before = budget.path().read_text()
    real_replace = budget.os.replace

    def boom(src, dst):
        raise OSError("disk full")

    monkeypatch.setattr(budget.os, "replace", boom)
    with pytest.raises(OSError):
        budget._write(budget.path(), {"month": budget.current_month(), "openrouter_usd": 9.0, "anthropic_usd": 0.0, "requests": 0})
    monkeypatch.setattr(budget.os, "replace", real_replace)
    assert budget.path().read_text() == before  # the old content is whole
    assert sorted(p.name for p in data_dir.iterdir()) == ["budget.json"]  # no temporary file left


def test_a_failing_write_does_not_fail_the_request(monkeypatch):
    monkeypatch.setattr(budget, "_write", lambda *a, **k: (_ for _ in ()).throw(OSError("read-only")))
    assert budget.add(openrouter_usd=1.0)["openrouter_usd"] == 1.0  # the caller carries on


def test_a_writer_never_leaves_a_partial_file_for_a_reader(monkeypatch):
    stop = threading.Event()
    bad: list[str] = []

    def reader():
        while not stop.is_set():
            try:
                json.loads(budget.path().read_text())
            except FileNotFoundError:
                pass
            except ValueError:
                bad.append("partial")

    budget.add(requests=1)
    thread = threading.Thread(target=reader)
    thread.start()
    for _ in range(150):
        budget.add(openrouter_usd=0.001, anthropic_usd=0.002)
    stop.set()
    thread.join()
    assert bad == []


def test_concurrent_adds_are_all_counted():
    def work():
        for _ in range(25):
            budget.add(openrouter_usd=0.01, anthropic_usd=0.02, requests=1)

    threads = [threading.Thread(target=work) for _ in range(8)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    state = budget.state()
    assert state["requests"] == 200 and state["openrouter_usd"] == pytest.approx(2.0) and state["anthropic_usd"] == pytest.approx(4.0)


@pytest.mark.parametrize(
    "content",
    ["not json", "[]", '{"month": "2026-10"}', '{"month": "2026-10", "openrouter_usd": -1, "anthropic_usd": 0, "requests": 0}',
     '{"month": 5, "openrouter_usd": 0, "anthropic_usd": 0, "requests": 0}',
     '{"month": "2026-10", "openrouter_usd": 0, "anthropic_usd": 0, "requests": 1.5}'],
)  # fmt: skip
def test_a_damaged_file_is_set_aside_and_counting_starts_again(data_dir, content):
    data_dir.mkdir(parents=True)
    budget.path().write_text(content)
    assert budget.state()["openrouter_usd"] == 0.0
    assert not budget.path().exists() and len(list(data_dir.glob("budget.json.damaged-*"))) == 1
    budget.add(openrouter_usd=0.5)
    assert json.loads(budget.path().read_text())["openrouter_usd"] == 0.5
