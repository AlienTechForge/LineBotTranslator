"""Mode B verification: digit integrity, leftover source language, verdicts, attempt selection."""

import pytest
from scenario import ocr

from app import verify


def words(*items):
    return ocr(*items).words


# ---------------------------------------------------------------- digit runs


def test_digit_runs_allow_one_separator_between_digits_and_strip_it():
    runs = lambda text: verify.extract_digit_runs(text)  # noqa: E731
    assert runs("Tel 0912-345-678") == [("0912345678", "0912-345-678")]
    assert runs("12:30") == [("1230", "12:30")]
    assert runs("NT$1,280.50") == [("128050", "1,280.50")]
    assert runs("2024/05/03") == [("20240503", "2024/05/03")]
    assert runs("A1234B") == [("1234", "1234")]
    assert runs("no digits, 7 alone, 1 - 2 spaced") == []  # 1-digit runs and spaced digits are not runs
    assert runs("1,,2") == []  # only a single separator may sit between two digits
    assert runs("12 34") == [("12", "12"), ("34", "34")]


def test_full_width_and_foreign_digits_are_folded_to_ascii():
    assert verify.extract_digit_runs("２０２４／０５／０３") == [("20240503", "2024/05/03")]
    assert verify.digit_string("tel ١٢٣٤ and ５６") == "123456"


def test_changed_handwritten_phone_number_is_a_missing_run():
    # the round-2 defect: 77446716 came back as 09446916
    source = words("TEL", "77446716", "Total", "1,200")
    result = verify.check_digits(source, "TEL 09446916 Total 1,200")
    assert result["digits_ok"] is False
    assert result["missing_digit_runs"] == ["77446716"]
    assert result["missing_digit_details"] == [{"run": "77446716", "as_written": "77446716"}]
    assert result["source_digit_runs"] == 2


def test_digits_survive_reformatting_and_new_spacing():
    source = words("0912-345-678", "12:30", "2024/05/03")
    out = "Phone: 0912 345 678\nOpen 12:30\nDate 2024-05-03"
    assert verify.check_digits(source, out) == {
        "digits_ok": True, "missing_digit_runs": [], "missing_digit_details": [], "source_digit_runs": 3,
    }  # fmt: skip


def test_a_run_repeated_in_the_source_is_checked_and_reported_once():
    # "30" is printed three times in the source: one distinct run, found once is enough
    short = verify.check_digits(words("30", "30", "30"), "x 30 y")
    assert short["digits_ok"] and short["source_digit_runs"] == 1
    gone = verify.check_digits(words("30", "30", "30"), "nothing here")
    assert gone["missing_digit_runs"] == ["30"] and len(gone["missing_digit_details"]) == 1
    # the same long run on two lines (TEL / FAX): both copies come from one distinct run
    twice = words("TEL 77446716", "FAX 77446716", "77446716")
    assert verify.check_digits(twice, "77446716")["digits_ok"]
    changed = verify.check_digits(twice, "09446916 and 09446916")
    assert changed["missing_digit_runs"] == ["77446716"] and changed["source_digit_runs"] == 1
    # the same run written two ways is still one run
    mixed = verify.check_digits(words("12:30", "12.30", "1230"), "closes 12:30")
    assert mixed["source_digit_runs"] == 1 and mixed["digits_ok"]


def test_every_distinct_run_must_be_present_and_the_missing_ones_come_back_in_source_order():
    source = words("0912-345-678", "2024/05/03", "12:30", "NT$1,280")
    result = verify.check_digits(source, "Phone 0912345678 date 20240503")
    assert result["missing_digit_runs"] == ["1230", "1280"]
    assert [d["as_written"] for d in result["missing_digit_details"]] == ["12:30", "1,280"]
    assert result["source_digit_runs"] == 4 and result["digits_ok"] is False


def test_the_run_only_has_to_be_a_substring_of_the_output_digit_string():
    # digits of the output are concatenated: a run split by letters or spaces in the output still counts,
    # and a run inside a longer number counts too (the rule is "substring", not "whole number")
    assert verify.check_digits(words("77446716"), "TEL 7744 6716")["digits_ok"]
    assert verify.check_digits(words("77446716"), "ID 1977446716 2")["digits_ok"]
    assert not verify.check_digits(words("77446716"), "7744 x 6717")["digits_ok"]


def test_no_digits_in_the_source_means_digits_ok():
    assert verify.check_digits(words("hello", "world"), "anything 123")["digits_ok"] is True


# ---------------------------------------------------------------- scripts and languages


@pytest.mark.parametrize(
    "ch, script",
    [("日", "Han"), ("か", "Kana"), ("カ", "Kana"), ("한", "Hangul"), ("a", "Latin"), ("é", "Latin"),
     ("đ", "Latin-vi"), ("ạ", "Latin-vi"), ("ㄅ", "Bopomofo"), ("ж", "Cyrillic"), ("5", None), (".", None), (" ", None)],
)  # fmt: skip
def test_char_script(ch, script):
    assert verify.char_script(ch) == script


def test_source_language_is_the_majority_of_the_detected_languages():
    ws = words(("こんにちは", "ja"), ("世界", "ja"), ("Hello", "en"), ("お願い", "ja"), ("12", "ja"))
    assert verify.detect_source_language(ws) == ("ja", "detected")
    assert verify.detect_source_language(words(("Xin", "vi"), ("chào", "vi"), ("bạn", "vi"), ("Hello", "en"))) == ("vi", "detected")


def test_kanji_tagged_zh_inside_a_japanese_text_is_still_japanese():
    ws = words(("営業時間", "zh"), ("定休日", "zh"), ("料金表", "zh"), ("メニュー", "ja"), ("ございます", "ja"))
    assert verify.detect_source_language(ws)[0] == "ja"
    assert verify.detect_source_language(words(("营业时间", "zh"), ("价格表", "zh"), ("电话", "zh")))[0] == "zh"


def test_source_language_falls_back_to_script_detection_without_detected_languages():
    assert verify.detect_source_language(words("ごはん", "おいしい", "料理")) == ("ja", "script")
    assert verify.detect_source_language(words("营业时间", "价格表")) == ("zh", "script")
    assert verify.detect_source_language(words("안녕하세요", "감사합니다")) == ("ko", "script")
    assert verify.detect_source_language(words("Hello", "world", "menu")) == ("en", "script")
    # Latin with Vietnamese-only letters (ă đ ơ ư, tone marks on ê â ô ...) -> Vietnamese
    vi = words("Xin", "chào", "các", "bạn", "đến", "với", "cửa", "hàng", "chúng", "tôi")
    assert verify.detect_source_language(vi) == ("vi", "script")
    assert verify.detect_source_language(words("12", "..", "!")) == (None, "none")


# ---------------------------------------------------------------- leftover source language


def test_leftover_counts_source_language_words_that_survive_in_the_output():
    source = words(*[("こんにちは", "ja")] * 10)
    out = words(("こんにちは", "ja"), ("Hello", "en"), ("World", "en"), ("Menu", "en"))
    result = verify.check_leftover(source, out, "en", "ja")
    assert result["leftover_count"] == 1 and result["source_language_words"] == 10
    assert result["leftover_ratio"] == 0.1 and result["leftover_ok"] is False
    assert result["leftover_words"] == ["こんにちは"]


def test_threshold_is_inclusive_at_eight_percent():
    source = words(*[("こんにちは", "ja")] * 25)
    english = [("Hello", "en")] * 23
    ok = verify.check_leftover(source, words(*english, ("こんにちは", "ja"), ("こんにちは", "ja")), "en", "ja")
    assert ok["leftover_ratio"] == 0.08 and ok["leftover_ok"] is True
    bad = verify.check_leftover(source, words(*english, *[("こんにちは", "ja")] * 3), "en", "ja")
    assert bad["leftover_ratio"] == 0.12 and bad["leftover_ok"] is False
    assert verify.LEFTOVER_RATIO_MAX == 0.08


def test_leftover_ignores_digits_punctuation_one_char_words_urls_and_target_script_words():
    source = words(*[("料理", "ja")] * 4)
    out = words(
        ("12345", "ja"),  # digit-only
        ("...", "ja"),  # punctuation
        ("店", "ja"),  # 1 character
        ("www.example.jp", "ja"),  # URL
        ("info@example.jp", "ja"),  # e-mail
        ("料理", "ja"),  # kanji only, written in the target's script (zh-TW): proves nothing
        ("おいしい", "ja"),  # kana: a real leftover
    )
    result = verify.check_leftover(source, out, "zh-TW", "ja")
    assert result["leftover_words"] == ["おいしい"] and result["leftover_count"] == 1


def test_leftover_with_mixed_scripts_counts_a_word_with_any_foreign_letter():
    source = words(*[("日本語のメニュー", "ja")] * 5)
    out = words(("日本語のメニュー", "ja"), ("Menu", "en"), ("ABメニュー", "ja"), ("价格", "zh"))
    result = verify.check_leftover(source, out, "zh-CN", "ja")
    # 日本語のメニュー and ABメニュー hold kana / Latin letters outside Han; 价格 is Han (target script) and zh-tagged
    assert result["leftover_words"] == ["日本語のメニュー", "ABメニュー"]
    assert result["leftover_count"] == 2 and result["leftover_ratio"] == 0.4


def test_han_only_word_tagged_zh_or_ja_is_the_same_language_for_a_korean_target():
    source = words(*[("料金表", "ja")] * 4)
    out = words(("料金表", "zh"), ("料金表", "ja"), ("요금표", "ko"))
    result = verify.check_leftover(source, out, "ko", "ja")
    assert result["leftover_count"] == 2  # Han is not Korean's script, Vision's zh / ja pick is a coin toss


def test_leftover_falls_back_to_scripts_when_vision_gives_no_language():
    source = words("ごはん", "おいしい", "メニュー", "ありがとう")
    out = words("ごはん", "Delicious", "メニュー", "Thanks")
    result = verify.check_leftover(source, out, "en", "ja")
    assert result["leftover_words"] == ["ごはん", "メニュー"] and result["leftover_ratio"] == 0.5


def test_vietnamese_source_to_english_flags_words_with_vietnamese_letters():
    source = words(("Giá", "vi"), ("đặc", "vi"), ("biệt", "vi"), ("cửa", "vi"), ("hàng", "vi"))
    out = words(("Price", "en"), ("đặc", "vi"), ("special", "en"), ("shop", "vi"))
    result = verify.check_leftover(source, out, "en", "vi")
    # "shop" is plain Latin (also English script) so it is not counted even though Vision tagged it vi
    assert result["leftover_words"] == ["đặc"] and result["leftover_count"] == 1


def test_first_twenty_leftover_words_are_listed_unique_and_in_order():
    source = words(*[("ごはん", "ja")] * 40)
    out = words(*[(f"ごはん{chr(0x3042 + i)}", "ja") for i in range(30)], ("ごはん0", "ja"))
    result = verify.check_leftover(source, out, "en", "ja")
    assert result["leftover_count"] == 31 and len(result["leftover_words"]) == 20
    assert result["leftover_words"][0] == "ごはんあ" and len(set(result["leftover_words"])) == 20


def test_no_source_language_words_means_no_leftover():
    result = verify.check_leftover(words("123", "45"), words("hello"), "en", None)
    assert result["leftover_ok"] and result["leftover_ratio"] == 0.0 and result["leftover_words"] == []


# ---------------------------------------------------------------- verdicts and attempt selection


@pytest.mark.parametrize(
    "digits_ok, leftover_ok, verdict",
    [(True, True, "pass"), (False, True, "digits-changed"), (True, False, "partial"), (False, False, "digits-changed+partial")],
)  # fmt: skip
def test_verdict_precedence(digits_ok, leftover_ok, verdict):
    assert verify.verdict_for(digits_ok, leftover_ok) == verdict


def v(verdict, missing=(), ratio=0.0):
    return {"verdict": verdict, "missing_digit_runs": list(missing), "leftover_ratio": ratio}


def test_attempt_selection_pass_beats_partial_beats_digits_changed():
    pass_, partial, digits, both = v("pass"), v("partial", ratio=0.2), v("digits-changed", ["1234"]), v("digits-changed+partial", ["1234"], 0.3)
    assert verify.choose_attempt([partial, pass_]) == 1
    assert verify.choose_attempt([pass_, partial]) == 0
    assert verify.choose_attempt([digits, partial]) == 1
    assert verify.choose_attempt([both, digits]) == 1
    assert verify.choose_attempt([both, partial]) == 1


def test_an_attempt_that_changed_digits_never_beats_one_that_did_not():
    # even a much worse translation wins over an attempt with altered digits
    assert verify.choose_attempt([v("partial", ratio=0.9), v("digits-changed", ["1234"], 0.0)]) == 0
    assert verify.choose_attempt([v("digits-changed", ["1234"]), v("partial", ratio=0.9)]) == 1


def test_ties_keep_the_first_attempt_and_inner_ties_prefer_fewer_problems():
    assert verify.choose_attempt([v("partial", ratio=0.2), v("partial", ratio=0.2)]) == 0
    assert verify.choose_attempt([v("partial", ratio=0.2), v("partial", ratio=0.1)]) == 1
    assert verify.choose_attempt([v("digits-changed", ["1111", "2222"]), v("digits-changed", ["1111"])]) == 1
    assert verify.choose_attempt([v("digits-changed", ["1111"]), v("digits-changed", ["1111", "2222"])]) == 0


def test_an_unverified_attempt_never_replaces_a_verified_one():
    assert verify.choose_attempt([v("digits-changed+partial", ["1"]), None]) == 0
    assert verify.choose_attempt([None, v("partial", ratio=0.5)]) == 1
    assert verify.choose_attempt([{"verdict": "unverified"}, v("pass")]) == 1
    assert verify.choose_attempt([None]) == 0


def test_verify_output_end_to_end_on_scripted_ocr():
    source = ocr(("TEL", "en"), ("77446716", "en"), ("営業時間", "ja"), ("メニュー", "ja"), ("ご飯", "ja"), ("定食", "ja"), size=(100, 100))
    good = ocr(("TEL", "en"), ("77446716", "en"), ("Hours", "en"), ("Menu", "en"), ("Rice", "en"), ("Set", "en"), size=(100, 100))
    report = verify.verify_output(source, good, "en", include_text=True)
    assert report["verdict"] == "pass" and report["digits_ok"] and report["leftover_ok"]
    assert report["source_language"] == "ja" and report["source_language_method"] == "detected"
    assert report["target_language"] == "en" and report["output_text"] == "TEL 77446716 Hours Menu Rice Set"

    bad = ocr(("TEL", "en"), ("09446916", "en"), ("営業時間", "ja"), ("Menu", "en"), ("Rice", "en"), ("Set", "en"), size=(100, 100))
    report = verify.verify_output(source, bad, "en")
    assert report["verdict"] == "digits-changed+partial"
    assert report["missing_digit_runs"] == ["77446716"] and report["leftover_words"] == ["営業時間"]
    assert "output_text" not in report and report["thresholds"]["leftover_ratio_max"] == 0.08


def test_the_output_digits_come_from_the_words_when_vision_gave_no_full_text():
    source = ocr(("TEL", "en"), ("77446716", "en"), size=(100, 100))
    no_text = ocr(("TEL", "en"), ("09446916", "en"), text="", size=(100, 100))
    assert verify.verify_output(source, no_text, "en", include_text=True)["missing_digit_runs"] == ["77446716"]
    kept = ocr(("TEL", "en"), ("77446716", "en"), text="", size=(100, 100))
    report = verify.verify_output(source, kept, "en", include_text=True)
    assert report["verdict"] == "pass" and report["output_text"] == "TEL 77446716"


def test_an_empty_source_has_nothing_to_compare_and_says_so():
    report = verify.verify_output(ocr(size=(100, 100)), ocr(("Hello", "en"), size=(100, 100)), "en")
    assert report["verdict"] == "pass" and "nothing to compare" in report["note"]
    assert report["source_language"] is None and report["source_digit_runs"] == 0
