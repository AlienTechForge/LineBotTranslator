"""vision.ocr_words keeps the detected language of every word (word, else paragraph, block, page)."""

import enum
from types import SimpleNamespace as NS

from app import vision
from app.models import base_language


class Brk(enum.IntEnum):
    UNKNOWN = 0
    SPACE = 1


def langs(*pairs):
    return NS(detected_languages=[NS(language_code=code, confidence=conf) for code, conf in pairs])


def word(text, *lang_pairs):
    verts = [NS(x=0, y=0), NS(x=10, y=0), NS(x=10, y=10), NS(x=0, y=10)]
    symbols = [NS(text=ch, property=NS(detected_break=NS(type_=Brk.SPACE))) for ch in text]
    return NS(bounding_box=NS(vertices=verts), symbols=symbols, confidence=0.9, property=langs(*lang_pairs))


def response(page_langs, block_langs, para_langs, words):
    paragraph = NS(words=words, property=langs(*para_langs))
    block = NS(paragraphs=[paragraph], property=langs(*block_langs))
    page = NS(width=100, height=50, blocks=[block], property=langs(*page_langs))
    return NS(error=None, full_text_annotation=NS(pages=[page], text="line one\nline two"))


def run(monkeypatch, resp):
    monkeypatch.setattr(vision, "_annotate", lambda image_bytes: resp)
    return vision.ocr_words(b"x", 100, 50)


def test_word_language_wins_and_the_highest_confidence_one_is_taken(monkeypatch):
    resp = response([("en", 0.9)], [("en", 0.9)], [("en", 0.9)], [word("ごはん", ("zh", 0.2), ("ja", 0.7))])
    result = run(monkeypatch, resp)
    assert [w.lang for w in result.words] == ["ja"]
    assert result.words[0].detected_languages == [("ja", 0.7), ("zh", 0.2)]  # the word's own guesses, best first
    assert result.text == "line one\nline two"  # the full OCR text comes along


def test_missing_word_language_falls_back_to_paragraph_then_block_then_page(monkeypatch):
    assert run(monkeypatch, response([("en", 1)], [("vi", 1)], [("ko", 1)], [word("가나")])).words[0].lang == "ko"
    assert run(monkeypatch, response([("en", 1)], [("vi", 1)], [], [word("xin")])).words[0].lang == "vi"
    assert run(monkeypatch, response([("fr", 1)], [], [], [word("bon")])).words[0].lang == "fr"
    assert run(monkeypatch, response([], [], [], [word("???")])).words[0].lang is None


def test_detected_languages_are_the_words_own_guesses_only(monkeypatch):
    # no guess for the word itself: `lang` inherits the paragraph's, `detected_languages` stays empty
    inherited = run(monkeypatch, response([("en", 1)], [("vi", 1)], [("ko", 1)], [word("가나")])).words[0]
    assert inherited.lang == "ko" and inherited.detected_languages == []
    # two codes with the same base collapse to the higher confidence
    merged = run(monkeypatch, response([], [], [], [word("甲", ("zh-Hant", 0.4), ("zh", 0.6), ("ja", 0.1))])).words[0]
    assert merged.detected_languages == [("zh", 0.6), ("ja", 0.1)] and merged.lang == "zh"


def test_script_subtags_and_undetermined_codes_are_normalised(monkeypatch):
    words = [word("甲", ("zh-Hant", 0.9)), word("乙", ("und", 0.9)), word("丙", ("JA", 0.5))]
    result = run(monkeypatch, response([], [], [], words))
    assert [w.lang for w in result.words] == ["zh", None, "ja"]
    assert [w.id for w in result.words] == ["w0001", "w0002", "w0003"]
    assert base_language("zh_TW") == "zh" and base_language(None) is None and base_language("") is None
