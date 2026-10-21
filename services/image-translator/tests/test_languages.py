"""The language table, how a code is normalised, and the fonts of the scripts Noto Sans does not cover."""

import shutil
from pathlib import Path

import pytest

from app import claude_units, config, fonts, judge, openrouter, verify
from app.layout import contains_cjk

REQUIRED = (
    "en zh-TW zh-CN zh-HK ja ko vi th id ms tl fr de es it pt ru ar hi tr nl pl uk".split()
)  # the codes the brief names; he is there for the Hebrew face


def test_the_table_has_every_language_of_the_bot_with_an_english_name():
    for code in REQUIRED:
        assert code in config.LANGUAGES, code
        name = config.LANGUAGES[code]
        assert name.isascii() and name[0].isupper(), (code, name)
    assert config.LANGUAGES["en"] == "English" and config.LANGUAGES["zh-TW"] == "Traditional Chinese (Taiwan)"
    assert config.LANGUAGES["zh-HK"].startswith("Traditional Chinese") and "Hong Kong" in config.LANGUAGES["zh-HK"]
    assert len(set(config.LANGUAGES.values())) == len(config.LANGUAGES)


@pytest.mark.parametrize(
    "given, expected",
    [
        ("en", ("en", True)), ("EN", ("en", True)), ("zh-TW", ("zh-TW", True)), ("zh-tw", ("zh-TW", True)), ("zh_TW", ("zh-TW", True)),
        ("zh", ("zh-TW", True)), ("zh-Hant", ("zh-TW", True)), ("zh-Hant-TW", ("zh-TW", True)), ("tw", ("zh-TW", True)),
        ("zh-CN", ("zh-CN", True)), ("zh-Hans", ("zh-CN", True)), ("cn", ("zh-CN", True)), ("zh-SG", ("zh-CN", True)),
        ("zh-HK", ("zh-HK", True)), ("zh-Hant-HK", ("zh-HK", True)), ("zh-MO", ("zh-HK", True)),
        ("fr-FR", ("fr", True)), ("pt-BR", ("pt", True)), ("en-US", ("en", True)), ("es_MX", ("es", True)), ("fil", ("tl", True)),
        ("iw", ("he", True)), (" ja ", ("ja", True)),
        ("xx", ("xx", False)), ("sw", ("sw", False)), ("zh-XX", ("zh-XX", False)), ("", ("", False)),
    ],
)
def test_normalize_lang(given, expected):
    assert config.normalize_lang(given) == expected


def test_a_code_that_is_not_a_language_code_does_not_match_the_pattern():
    for good in ("en", "zh-TW", "sr-Latn-RS", "fil"):
        assert config.LANG_CODE_RE.match(good), good
    for bad in ("", "e", "zh TW", "en;rm", "zh-", "-en", "en\nzh", "a1", "zh-TW-x-y-z-w"):
        assert not config.LANG_CODE_RE.match(bad), repr(bad)


def test_prompts_use_the_code_itself_for_an_unknown_language():
    assert claude_units.language_name("xx") == "xx" and judge.language_name("xx") == "xx"
    assert "into xx" in openrouter.build_prompt("xx")
    assert "French" in openrouter.build_prompt("fr") and "Thai" in claude_units.build_user_text("th", 10, 10, "")


def test_every_language_of_the_table_has_the_scripts_the_leftover_check_needs():
    for code in config.LANGUAGES:
        base = code.split("-")[0]
        assert base in verify.TARGET_SCRIPTS, code


# ---------------------------------------------------------------- fonts


@pytest.fixture
def roots(monkeypatch, tmp_path):
    monkeypatch.setattr(fonts, "FONT_ROOTS", [tmp_path])
    for name in ("LAB_FONT_LATIN", "LAB_FONT_LATIN_BOLD", "LAB_FONT_CJK", "LAB_FONT_CJK_BOLD"):
        monkeypatch.delenv(name, raising=False)
    fonts.resolve_font.cache_clear()
    fonts.resolve_script_font.cache_clear()
    yield tmp_path
    fonts.resolve_font.cache_clear()
    fonts.resolve_script_font.cache_clear()


_SYSTEM_TTF = fonts.resolve_font(False).path


def some_ttf() -> Path:
    if _SYSTEM_TTF is None or not _SYSTEM_TTF.lower().endswith(".ttf"):
        pytest.skip("no TrueType font on this host to copy")
    return Path(_SYSTEM_TTF)


def put(root: Path, name: str) -> Path:
    target = root / "truetype" / "noto" / name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy(some_ttf(), target)
    return target


def test_zh_hk_is_drawn_like_zh_tw_and_counts_as_cjk():
    assert fonts.CJK_FACE["zh-HK"] == fonts.CJK_FACE["zh-TW"] == "TC"
    assert fonts.face_for_language("zh-HK") == "TC" and fonts.face_for_language("zh-Hant-HK") == "TC"
    for code in ("zh-TW", "zh-CN", "zh-HK", "ja", "ko", "zh-Hant", "zh-Hans", "zh"):
        assert fonts.is_cjk_lang(code), code
    for code in ("en", "th", "ar", "xx", "vi", "fr"):
        assert not fonts.is_cjk_lang(code), code


def test_a_thai_target_gets_the_noto_thai_face_when_it_is_installed(roots):
    put(roots, "NotoSans-Regular.ttf")
    thai = put(roots, "NotoSansThai-Regular.ttf")
    thai_bold = put(roots, "NotoSansThai-Bold.ttf")
    regular, bold = fonts.pick_font("th", "สวัสดี", False), fonts.pick_font("th", "สวัสดี", True)
    assert Path(regular.path) == thai and Path(bold.path) == thai_bold and not regular.cjk and not bold.synthetic_bold
    assert fonts.font_notes("th") == []
    assert Path(fonts.pick_font("en", "Hello", False).path).name == "NotoSans-Regular.ttf"  # other languages are unaffected


def test_thai_bold_is_synthesised_from_the_regular_face_when_only_that_is_installed(roots):
    thai = put(roots, "NotoSansThai-Regular.ttf")
    bold = fonts.pick_font("th", "x", True)
    assert Path(bold.path) == thai and bold.synthetic_bold


@pytest.mark.parametrize(
    "lang, names",
    [("ar", ["NotoSansArabic-Regular.ttf"]), ("he", ["NotoSansHebrew-Regular.ttf"]), ("hi", ["NotoSansDevanagari-Regular.ttf"]),
     ("th", ["NotoSansThaiUI-Regular.ttf"])],
)  # fmt: skip
def test_the_other_scripts_find_their_noto_face(roots, lang, names):
    installed = put(roots, names[0])
    assert Path(fonts.pick_font(lang, "x", False).path) == installed


def test_without_the_thai_face_it_falls_back_to_latin_and_warns(roots):
    dejavu = put(roots, "DejaVuSans.ttf")
    spec = fonts.pick_font("th", "สวัสดี", False)
    assert Path(spec.path) == dejavu
    notes = fonts.font_notes("th")
    assert len(notes) == 1 and "thai" in notes[0] and "fonts-noto-core" in notes[0]
    assert any("devanagari" in n for n in fonts.font_notes("hi"))


def test_right_to_left_languages_are_drawn_as_is_and_the_notes_say_so(roots):
    put(roots, "NotoSansArabic-Regular.ttf")
    notes = fonts.font_notes("ar")
    assert len(notes) == 1 and "right to left" in notes[0] and "without bidi or shaping" in notes[0]
    assert len(fonts.font_notes("he")) == 2  # no Hebrew face in this root, and right to left
    assert fonts.font_notes("en") == [] and fonts.font_notes("fr") == [] and fonts.font_notes("xx") == []


def test_a_cjk_target_without_any_cjk_font_says_so(roots):
    notes = fonts.font_notes("ja")
    assert any("no CJK font" in n for n in notes)


def test_text_with_cjk_characters_gets_a_cjk_font_whatever_the_target(roots):
    cjk = put(roots, "NotoSansCJK-Regular.ttc")
    assert Path(fonts.pick_font("th", "紅燒肉", False).path) == cjk and contains_cjk("紅燒肉")
