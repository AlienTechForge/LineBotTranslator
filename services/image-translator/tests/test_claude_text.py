"""Mode C, step 3: the text-only translation call (claude_text.py): the request, chunking, merging, the short rule."""

import base64
import io
import json
import logging
import re
import threading

import anthropic
import httpx2
import pytest
from PIL import Image

from app import claude_text, claude_units
from app.imaging import load_image
from app.models import ClaudeUnit, ModeError

LINE_RE = re.compile(r"^\[(\d+)\] (.*)$", re.M)


class Usage:
    def __init__(self, i=500, o=200):
        self.input_tokens, self.output_tokens = i, o


class Block:
    type = "text"

    def __init__(self, text):
        self.text = text


class Response:
    def __init__(self, items, stop_reason="end_turn", model="claude-opus-5-5", usage=None):
        self.content = [Block(json.dumps({"items": items}, ensure_ascii=False))]
        self.usage = usage or Usage()
        self.stop_reason, self.stop_details, self.model = stop_reason, None, model


def request_text(kwargs):
    return next(p["text"] for p in kwargs["messages"][0]["content"] if p["type"] == "text")


def default_answer(kwargs):
    """Translate every `[id] text` line of the request as `T:text`."""
    return [{"id": int(i), "translation": f"T:{t}", "keep": False, "short": ""} for i, t in LINE_RE.findall(request_text(kwargs))]


class FakeClient:
    """Records every create(); `answer(kwargs)` returns the items (a list) or a Response, or raises."""

    def __init__(self, answer=default_answer, barrier=None):
        self.answer, self.barrier, self.calls = answer, barrier, []
        self.lock = threading.Lock()
        client = self

        class Beta:
            class messages:
                @staticmethod
                def create(**kw):
                    with client.lock:
                        client.calls.append(kw)
                    if client.barrier is not None:
                        client.barrier.wait(timeout=10)  # every chunk must be in flight at the same time
                    out = client.answer(kw)
                    return out if isinstance(out, Response) else Response(out)

        self.beta = Beta
        self.messages = None


def bad_request():
    request = httpx2.Request("POST", "https://api.anthropic.com/v1/messages")
    return anthropic.BadRequestError("rejected", response=httpx2.Response(400, request=request), body=None)


def make_image(size=(2000, 1000)):
    buf = io.BytesIO()
    Image.new("RGB", size, (240, 240, 240)).save(buf, "PNG")
    return load_image(buf.getvalue())


def units_of(*texts, keep=()):
    return [
        ClaudeUnit(word_ids=[f"w{i:04d}"], source_text=t, keep=i in keep, translation=t if i in keep else "")
        for i, t in enumerate(texts, start=1)
    ]


@pytest.fixture
def install(monkeypatch):
    monkeypatch.setenv("ANTHROPIC_API_KEY", "test-anthropic-key-0000000000")

    def go(answer=default_answer, barrier=None):
        fake = FakeClient(answer, barrier)
        monkeypatch.setattr(anthropic, "Anthropic", lambda **kw: fake)
        return fake

    return go


# ---------------------------------------------------------------- the request


def test_the_request_is_a_numbered_list_of_the_units_that_are_not_kept_with_the_picture(install):
    fake = install()
    units = units_of("豚カツ定食", "¥1,180", "味噌汁", keep={2})
    call = claude_text.translate_units(units, make_image(), "zh-TW", "ja")
    (kw,) = fake.calls
    assert kw["model"] == "claude-opus-5-5" and kw["max_tokens"] == 8000 and kw["system"] == claude_text.SYSTEM_PROMPT
    assert kw["output_config"]["effort"] == "medium"
    assert kw["output_config"]["format"] == {"type": "json_schema", "schema": claude_text.ITEMS_SCHEMA}
    assert kw["betas"] == ["server-side-fallback-2026-07-01"] and kw["fallbacks"] == "default"  # the shared fallback ladder
    content = kw["messages"][0]["content"]
    assert [p["type"] for p in content] == ["image", "text"]
    sent = Image.open(io.BytesIO(base64.b64decode(content[0]["source"]["data"])))
    assert sent.format == "JPEG" and max(sent.size) == 1024 and content[0]["source"]["media_type"] == "image/jpeg"
    text = content[1]["text"]
    assert "Target language: Traditional Chinese (Taiwan) (zh-TW)" in text and "Source language: Japanese (ja)" in text
    assert text.endswith("Items:\n[1] 豚カツ定食\n[3] 味噌汁")  # ids are the unit numbers; the price is not sent
    assert "part" not in text.split("Items:")[0]  # one call: no note about a longer list
    assert [u.translation for u in call.units] == ["T:豚カツ定食", "¥1,180", "T:味噌汁"]
    assert call.sent_image_size == (1024, 512) and call.calls == 1 and call.chunk_sizes == [2]


def test_the_model_is_the_translate_model_else_the_planner_model(install, monkeypatch):
    fake = install()
    claude_text.translate_units(units_of("a b"), make_image(), "en")
    assert fake.calls[-1]["model"] == "claude-opus-5-5"
    monkeypatch.setenv("CLAUDE_MODEL", "claude-sonnet-5-5")
    claude_text.translate_units(units_of("a b"), make_image(), "en")
    assert fake.calls[-1]["model"] == "claude-sonnet-5-5"
    monkeypatch.setenv("CLAUDE_TRANSLATE_MODEL", "claude-haiku-5-5")
    call = claude_text.translate_units(units_of("a b"), make_image(), "en")
    assert fake.calls[-1]["model"] == "claude-haiku-5-5" and call.model == "claude-haiku-5-5"


def test_without_the_image_context_no_picture_is_sent(install, monkeypatch):
    fake = install()
    monkeypatch.setenv("MODE_C_IMAGE_CONTEXT", "0")
    call = claude_text.translate_units(units_of("a b"), make_image(), "en")
    assert [p["type"] for p in fake.calls[0]["messages"][0]["content"]] == ["text"] and call.sent_image_size is None


def test_the_cost_comes_from_the_price_table_and_the_fallback_ladder_is_the_shared_one(install):
    fake = install()
    call = claude_text.translate_units(units_of("a b"), make_image(), "en")
    assert (call.input_tokens, call.output_tokens) == (500, 200)
    assert call.cost_usd == pytest.approx((500 * 4 + 200 * 20) / 1e6) and call.fallback_form == "default"
    assert call.served_model == "claude-opus-5-5" and call.stop_reason == "end_turn"
    assert len(fake.calls) == 1


def test_a_rejected_default_form_steps_down_like_the_planner(install, monkeypatch):
    fake = install()
    forms = []

    def create(**kw):
        forms.append("array" if "fallbacks" in kw and kw["fallbacks"] != "default" else "default")
        if kw.get("fallbacks") == "default":
            raise bad_request()
        return Response(default_answer(kw))

    fake.beta.messages.create = staticmethod(create)
    call = claude_text.translate_units(units_of("a b"), make_image(), "en")
    assert forms == ["default", "array"] and call.fallback_form == "array" and call.attempts


# ---------------------------------------------------------------- chunks


def test_a_long_list_is_split_into_chunks_of_fifty_translated_in_parallel_and_merged_by_id(install):
    fake = install(barrier=threading.Barrier(3))  # all three calls must be in flight at the same moment
    units = units_of(*[f"item {i}" for i in range(1, 121)])
    call = claude_text.translate_units(units, make_image(), "en")
    assert len(fake.calls) == 3 and call.calls == 3 and call.chunk_sizes == [50, 50, 20]
    sizes = sorted(len(LINE_RE.findall(request_text(kw))) for kw in fake.calls)
    assert sizes == [20, 50, 50]
    for kw in fake.calls:  # every chunk carries the picture and says it is part of a longer list
        assert [p["type"] for p in kw["messages"][0]["content"]] == ["image", "text"]
        assert re.search(r"part \d of 3 of one longer list", request_text(kw))
    assert [u.translation for u in call.units] == [f"T:item {i}" for i in range(1, 121)]  # in the order of the units
    assert call.translated_ids == list(range(1, 121)) and call.missing_ids == []
    assert (call.input_tokens, call.output_tokens) == (1500, 600)  # summed over the calls
    assert call.cost_usd == pytest.approx(3 * (500 * 4 + 200 * 20) / 1e6)


def test_the_chunk_size_follows_mode_c_chunk_units(install, monkeypatch):
    fake = install()
    monkeypatch.setenv("MODE_C_CHUNK_UNITS", "10")
    call = claude_text.translate_units(units_of(*[f"item {i}" for i in range(25)]), make_image(), "en")
    assert call.chunk_sizes == [10, 10, 5] and len(fake.calls) == 3
    assert claude_text.chunks_of(list(range(7)), 3) == [[0, 1, 2], [3, 4, 5], [6]] and claude_text.chunks_of([], 3) == []


def test_exactly_fifty_units_are_one_call(install):
    fake = install()
    claude_text.translate_units(units_of(*[f"item {i}" for i in range(50)]), make_image(), "en")
    assert len(fake.calls) == 1


def test_a_missing_id_leaves_the_unit_kept_with_a_warning(install):
    def answer(kw):
        return [i for i in default_answer(kw) if i["id"] != 2] + [{"id": 99, "translation": "stray", "keep": False, "short": ""}]

    install(answer)
    units = units_of("one", "two", "three")
    call = claude_text.translate_units(units, make_image(), "en")
    assert call.missing_ids == [2] and call.translated_ids == [1, 3]
    assert call.units[1].keep is True and call.units[1].translation == "two" and call.units[1].notes == "no translation returned"
    assert [u.translation for u in call.units] == ["T:one", "two", "T:three"]
    assert any("1 unit(s) got no translation" in note and "u02" in note for note in call.notes)
    assert units[1].keep is False  # the caller's units are not changed


def test_a_chunk_that_fails_costs_only_its_own_units_and_every_chunk_failing_is_an_error(install):
    state = {"n": 0}

    def answer(kw):
        state["n"] += 1
        if "part 2 of" in request_text(kw):
            raise bad_request_status()
        return default_answer(kw)

    def bad_request_status():
        request = httpx2.Request("POST", "https://api.anthropic.com/v1/messages")
        return anthropic.InternalServerError("overloaded", response=httpx2.Response(500, request=request), body=None)

    install(answer)
    call = claude_text.translate_units(units_of(*[f"item {i}" for i in range(1, 121)]), make_image(), "en")
    assert call.missing_ids == list(range(51, 101))  # the second chunk
    assert call.units[50].keep is True and call.units[0].translation == "T:item 1"
    assert any("part 2 of 3 failed" in n for n in call.notes) and any("50 unit(s) got no translation" in n for n in call.notes)

    install(lambda kw: (_ for _ in ()).throw(bad_request_status()))
    with pytest.raises(ModeError):
        claude_text.translate_units(units_of("a b", "c d"), make_image(), "en")


def test_an_answer_that_is_not_the_schema_is_an_error(install):
    install(lambda kw: Response([], stop_reason="end_turn"))
    call = claude_text.translate_units(units_of("a b"), make_image(), "en")
    assert call.missing_ids == [1]  # an empty list is not an error, the unit is just not translated
    fake = install()
    fake.answer = lambda kw: Response.__new__(Response)  # no content at all
    with pytest.raises(ModeError):
        claude_text.translate_units(units_of("a b"), make_image(), "en")


def test_a_truncated_or_refused_answer_is_an_error(install):
    install(lambda kw: Response(default_answer(kw), stop_reason="max_tokens"))
    with pytest.raises(ModeError, match="max_tokens"):
        claude_text.translate_units(units_of("a b"), make_image(), "en")
    install(lambda kw: Response(default_answer(kw), stop_reason="refusal"))
    with pytest.raises(ModeError, match="refused"):
        claude_text.translate_units(units_of("a b"), make_image(), "en")


def test_without_an_api_key_it_says_so_and_nothing_is_called(monkeypatch):
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    monkeypatch.setattr(anthropic, "Anthropic", lambda **kw: pytest.fail("no key, no client"))
    with pytest.raises(ModeError, match="ANTHROPIC_API_KEY"):
        claude_text.translate_units(units_of("a b"), make_image(), "en")


def test_nothing_to_translate_means_no_call_at_all(monkeypatch):
    monkeypatch.setattr(anthropic, "Anthropic", lambda **kw: pytest.fail("every unit is kept: nothing to ask"))
    call = claude_text.translate_units(units_of("¥100", "A", keep={1, 2}), make_image(), "en")
    assert call.calls == 0 and call.cost_usd == 0.0 and [u.translation for u in call.units] == ["¥100", "A"]
    assert call.notes and "nothing to translate" in call.notes[0]


# ---------------------------------------------------------------- the answers


def test_a_translator_keep_makes_the_unit_a_kept_unit_with_its_own_text(install):
    install(lambda kw: [{"id": 1, "translation": "ignored", "keep": True, "short": "x"}, {"id": 2, "translation": "Rice", "keep": False, "short": ""}])
    call = claude_text.translate_units(units_of("Matsuya", "ご飯"), make_image(), "en")
    assert (call.units[0].keep, call.units[0].translation, call.units[0].short) == (True, "Matsuya", "")
    assert call.units[0].notes == "kept by the translator" and call.units[1].translation == "Rice" and not call.units[1].keep


def test_short_is_kept_only_when_the_translation_is_longer_than_1_4_times_the_source():
    assert claude_text.SHORT_RATIO == 1.4
    # latin to latin: 10 letters -> 15 letters is 1.5 x
    assert claude_text.clean_short("Pork cutl", "Pork cutlet set", "Cutlet set") == "Cutlet set"
    assert claude_text.clean_short("Pork cutl", "Pork cutlet", "Cutlet") == ""  # 11 / 9 = 1.22 x: no short wanted
    assert claude_text.clean_short("Pork cutl", "Pork cutlet s", "Cutlet") == "Cutlet"  # 13 / 9 = 1.44 x
    # a CJK character counts as two latin ones: 5 characters = 10 -> "Pork cutlet set" is 15
    assert claude_text.weighted_length("豚カツ定食") == 10 and claude_text.weighted_length("Pork cutlet set") == 15
    assert claude_text.clean_short("豚カツ定食", "Pork cutlet set", "Cutlet set") == "Cutlet set"  # 1.5 x
    assert claude_text.clean_short("豚カツ定食", "Pork cutlet", "Cutlet") == ""  # 11 / 10 = 1.1 x
    assert claude_text.clean_short("豚カツ定食", "炸豬排套餐", "套餐") == ""  # same width: never
    # exactly 1.4 x is not longer than 1.4 x
    assert claude_text.clean_short("0123456789", "01234567890123", "short") == ""
    assert claude_text.clean_short("0123456789", "012345678901234", "short") == "short"
    # a short that is not shorter than the translation is no short
    assert claude_text.clean_short("Pork cutl", "Pork cutlet set", "Pork cutlet set") == ""
    assert claude_text.clean_short("Pork cutl", "Pork cutlet set", "  ") == ""


def test_the_short_wording_of_the_model_goes_through_the_rule(install):
    install(lambda kw: [
        {"id": 1, "translation": "Pork cutlet set meal", "keep": False, "short": "Cutlet set"},  # 20 / 8: warranted
        {"id": 2, "translation": "Rice", "keep": False, "short": "R"},  # 4 / 4: not warranted, dropped
    ])  # fmt: skip
    call = claude_text.translate_units(units_of("Cutlet9", "Rice"), make_image(), "en")
    assert call.units[0].short == "Cutlet set" and call.units[1].short == ""


def test_the_user_text_names_the_languages_and_only_a_known_source_language():
    text = claude_text.build_user_text("zh-TW", None, ["[1] a b"])
    assert text == "Target language: Traditional Chinese (Taiwan) (zh-TW)\nItems:\n[1] a b"
    text = claude_text.build_user_text("en", "ja", ["[1] a", "[2] b"], (2, 3))
    assert "Source language: Japanese (ja)" in text and "part 2 of 3 of one longer list" in text
    assert claude_text.item_line(7, ClaudeUnit(word_ids=["w1"], source_text="two\nlines  here")) == "[7] two lines here"


def test_the_prompt_says_what_the_brief_asks_and_the_schema_has_four_required_fields():
    prompt = claude_text.SYSTEM_PROMPT
    for needle in ("Translate every item", "consistent", "Proper names, brand names, shop names and product codes", "keep to true",
                   "1.4 times", "2 Latin letters", "No explanations", "Return only the JSON object"):  # fmt: skip
        assert needle in prompt, needle
    item = claude_text.ITEMS_SCHEMA["properties"]["items"]["items"]
    assert item["required"] == ["id", "translation", "keep", "short"] and item["additionalProperties"] is False


def test_nothing_about_the_image_or_the_text_is_logged(install, caplog):
    install()
    with caplog.at_level(logging.DEBUG):
        claude_text.translate_units(units_of("極秘メニュー", "secret dish"), make_image(), "en", "ja")
    logged = "\n".join(r.getMessage() for r in caplog.records)
    assert "stage=claude-text" in logged and "極秘" not in logged and "secret" not in logged and "T:" not in logged


# ---------------------------------------------------------------- mock


def test_mock_mode_translates_offline_with_the_text_wrapped_in_the_language_code(monkeypatch):
    monkeypatch.setenv("LAB_MOCK", "1")
    monkeypatch.setattr(anthropic, "Anthropic", lambda **kw: pytest.fail("mock mode must not build a Claude client"))
    units = units_of("TONKATSU SET MENU", "NT$120", "紅燒肉飯店", "SET", keep={2})
    call = claude_text.translate_units(units, make_image(), "en")
    assert call.fallback_form == "mock" and call.calls == 1 and call.missing_ids == []
    assert [u.translation for u in call.units] == ["[en] TONKATSU SET MENU", "NT$120", "[en] 紅燒肉飯店", "[en] SET"]
    assert call.units[1].keep is True  # the price keeps its text
    assert [u.short for u in call.units] == ["", "", "紅燒肉飯店", "SET"]  # the short rule applies to the canned answer, too
    assert call.input_tokens > 0 and call.output_tokens > 0 and call.cost_usd == claude_units.estimate_cost(
        "claude-opus-5-5", call.input_tokens, call.output_tokens
    )
