"""Mode B verification: digit integrity, leftover source language, verdicts, attempt selection."""

import itertools

import pytest
from scenario import judge_record, ocr

from app import verify
from app.models import polygon_height


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
    result = verify.check_digits(source, out)
    assert result["digits_ok"] is True and result["source_digit_runs"] == 3
    assert result["missing_digit_runs"] == [] and result["missing_digit_details"] == []
    assert result["unverified_digit_runs"] == [] and [d["status"] for d in result["digit_runs"]] == ["ok"] * 3


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


def v(verdict, missing=(), ratio=0.0, coverage=None):
    return {"verdict": verdict, "missing_digit_runs": list(missing), "leftover_ratio": ratio, "coverage": coverage}


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


# ---------------------------------------------------------------- round 4: soft (handwriting) and hard digit runs


def soft_source(*items):
    """Source words as (text, confidence); the language is irrelevant for the digit check."""
    return ocr(*[(text, "en", conf) for text, conf in items]).words


def statuses(result):
    return {d["run"]: d["status"] for d in result["digit_runs"]}


def test_the_round_3_false_alarm_a_misread_handwritten_phone_number_no_longer_fails():
    # Vision read the handwritten 77446716 as 97446716 (confidence 0.52); the model reproduced the real number
    result = verify.check_digits(soft_source(("TEL", 0.99), ("97446716", 0.52)), "TEL 77446716")
    assert result["digits_ok"] is True and result["missing_digit_runs"] == []
    assert result["unverified_digit_runs"] == []  # matched within one digit: not even a warning
    (run,) = result["digit_runs"]
    assert (run["run"], run["soft"], run["confidence"], run["status"], run["matched"]) == ("97446716", True, 0.52, "near", "77446716")
    assert result["soft_digit_runs"] == 1


def test_a_soft_run_that_is_really_gone_is_a_warning_and_never_fails():
    result = verify.check_digits(soft_source(("77446716", 0.5)), "TEL 09446916")  # 3 digits differ
    assert result["digits_ok"] is True and result["missing_digit_runs"] == []
    assert result["unverified_digit_runs"] == ["77446716"]
    assert result["unverified_digit_details"] == [{"run": "77446716", "as_written": "77446716", "confidence": 0.5}]
    assert statuses(result) == {"77446716": "unverified"}


def test_the_same_one_digit_difference_fails_for_a_hard_run():
    result = verify.check_digits(soft_source(("97446716", 0.9)), "TEL 77446716")
    assert result["digits_ok"] is False and result["missing_digit_runs"] == ["97446716"]
    assert result["unverified_digit_runs"] == [] and statuses(result) == {"97446716": "missing"}
    assert result["digit_runs"][0]["soft"] is False


@pytest.mark.parametrize(
    "confidence, soft",
    [(0.7499, True), (0.75, False), (0.75001, False), (0.0, False), (0.01, True), (0.99, False)],
)
def test_the_soft_threshold_is_below_0_75_and_an_unreported_confidence_is_hard(confidence, soft):
    result = verify.check_digits(soft_source(("12345678", confidence)), "nothing")
    assert result["digit_runs"][0]["soft"] is soft
    assert result["digits_ok"] is soft  # a hard miss fails, a soft one does not
    assert (result["unverified_digit_runs"] == ["12345678"]) is soft


def test_short_soft_runs_are_matched_exactly_and_only_reported_never_failed():
    found = verify.check_digits(soft_source(("1230", 0.4)), "open 12:30")
    assert statuses(found) == {"1230": "ok"} and found["unverified_digit_runs"] == []
    off_by_one = verify.check_digits(soft_source(("1230", 0.4)), "open 12:31")  # no tolerance below 5 digits
    assert off_by_one["digits_ok"] is True and off_by_one["unverified_digit_runs"] == ["1230"]
    assert statuses(off_by_one) == {"1230": "unverified"}


def test_the_tolerance_starts_at_five_digits_and_needs_the_same_length():
    assert statuses(verify.check_digits(soft_source(("12345", 0.5)), "12346")) == {"12345": "near"}
    assert statuses(verify.check_digits(soft_source(("1234", 0.5)), "1235")) == {"1234": "unverified"}
    # one digit dropped or added is two edits away for this rule: same length only
    assert statuses(verify.check_digits(soft_source(("77446716", 0.5)), "7744716")) == {"77446716": "unverified"}
    assert statuses(verify.check_digits(soft_source(("77446716", 0.5)), "x 774467a6 y")) == {"77446716": "unverified"}
    # two digits off is not near
    assert statuses(verify.check_digits(soft_source(("77446716", 0.5)), "TEL 77446799")) == {"77446716": "unverified"}


def test_a_soft_run_is_compared_with_the_output_runs_not_with_windows_of_the_digit_soup():
    # the output digits concatenated hold 7744 6716 inside "x77446717y", but only a RUN of the same length counts
    result = verify.check_digits(soft_source(("77446716", 0.5)), "a 7744 b 6717")
    assert statuses(result) == {"77446716": "unverified"}  # runs are 7744 and 6717, not 77446717


def test_a_run_is_hard_when_any_word_it_comes_from_is_confident():
    # the same number printed (confident) and handwritten (soft): the printed copy makes it hard
    result = verify.check_digits(soft_source(("77446716", 0.4), ("TEL", 0.9), ("77446716", 0.93)), "nothing")
    assert result["missing_digit_runs"] == ["77446716"] and result["digit_runs"][0]["confidence"] == 0.93
    assert result["source_digit_runs"] == 1 and result["soft_digit_runs"] == 0
    only_soft = verify.check_digits(soft_source(("77446716", 0.4), ("77446716", 0.6)), "nothing")
    assert only_soft["digits_ok"] and only_soft["unverified_digit_runs"] == ["77446716"]


def test_a_soft_miss_does_not_change_the_verdict_but_a_hard_miss_does():
    source = ocr(("TEL", "en", 0.99), ("77446716", "en", 0.5), ("Total", "en", 0.99), ("1,200", "en", 0.95), size=(100, 100))
    soft_gone = ocr(("TEL", "en"), ("09446916", "en"), ("Total", "en"), ("1,200", "en"), size=(100, 100))
    report = verify.verify_output(source, soft_gone, "en")
    assert report["verdict"] == "pass" and report["unverified_digit_runs"] == ["77446716"]
    assert report["thresholds"]["soft_confidence_max"] == 0.75 and report["thresholds"]["tolerant_min_digits"] == 5
    hard_gone = ocr(("TEL", "en"), ("77446716", "en"), ("Total", "en"), ("1,300", "en"), size=(100, 100))
    report = verify.verify_output(source, hard_gone, "en")
    assert report["verdict"] == "digits-changed" and report["missing_digit_runs"] == ["1200"]
    assert report["unverified_digit_runs"] == []


# ---------------------------------------------------------------- round 4: big leftover glyphs


def test_polygon_height_is_the_short_side_whatever_the_rotation_or_writing_direction():
    assert polygon_height([(0, 0), (100, 0), (100, 20), (0, 20)]) == 20  # a horizontal word
    assert polygon_height([(0, 0), (20, 0), (20, 100), (0, 100)]) == 20  # a vertical column: the glyph width
    turned = [(50, 0), (50, 100), (30, 100), (30, 0)]  # a word on a page turned 90 degrees, Vision vertex order
    assert polygon_height(turned) == pytest.approx(20)
    assert polygon_height([(0, 0), (1, 1)]) == 0.0  # not a polygon


def title_case(leftover_height, others=299, ratio_words=300):
    """A Japanese source of 300 words; the English output has 299 words 10 px tall and one leftover kana word."""
    source = ocr(*[("こんにちは", "ja")] * ratio_words, size=(100, 100))
    out = ocr(*[("Hello", "en")] * others, ("メニュー", "ja", 0.9, leftover_height), size=(100, 100))
    return source, out


def test_a_leftover_word_more_than_twice_the_median_height_makes_it_partial_whatever_the_ratio():
    # round 3: the second line of a poster title stayed Vietnamese, 3 words of 260 (0.0115): not `partial`
    source, out = title_case(26)  # 2.6 x the median 10
    report = verify.verify_output(source, out, "en")
    assert report["leftover_ratio"] == pytest.approx(1 / 300, abs=1e-4) and report["leftover_ratio_ok"] is True
    assert report["leftover_ok"] is False and report["verdict"] == "partial"
    assert report["big_leftover_words"] == [{"word": "メニュー", "height": 26.0, "median_height": 10.0, "ratio": 2.6}]
    assert report["median_word_height"] == 10.0 and report["thresholds"]["big_glyph_ratio"] == 2.0


def test_exactly_twice_the_median_is_not_big_and_a_big_translated_word_is_fine():
    source, out = title_case(20)
    report = verify.verify_output(source, out, "en")
    assert report["verdict"] == "pass" and report["big_leftover_words"] == []
    # a big English title (translated) next to small body text is the normal case
    source = ocr(*[("こんにちは", "ja")] * 20, size=(100, 100))
    out = ocr(*[("Hello", "en")] * 19, ("MENU", "en", 0.9, 60), size=(100, 100))
    assert verify.verify_output(source, out, "en")["verdict"] == "pass"


def test_the_median_is_the_median_of_all_output_words_and_the_word_is_listed_once():
    source = ocr(*[("こんにちは", "ja")] * 100, size=(100, 100))
    # most words of this output are tall (25): a 40 px leftover is only 1.6 x the median
    tall = ocr(*[("Hello", "en", 0.9, 25)] * 60, *[("World", "en")] * 39, ("メニュー", "ja", 0.9, 40), size=(100, 100))
    assert verify.verify_output(source, tall, "en")["big_leftover_words"] == []
    # the same big word printed twice is one entry
    out = ocr(*[("Hello", "en")] * 90, ("メニュー", "ja", 0.9, 40), ("メニュー", "ja", 0.9, 40), size=(100, 100))
    report = verify.verify_output(source, out, "en")
    assert [b["word"] for b in report["big_leftover_words"]] == ["メニュー"] and report["verdict"] == "partial"


def test_without_recorded_heights_the_polygon_is_used():
    ws = ocr(("Hello", "en"), ("メニュー", "ja", 0.9, 40)).words
    assert [w.height for w in ws] == [0.0, 0.0]  # never recorded by Vision here
    assert [w.box_height for w in ws] == [10.0, 40.0]  # computed from the polygon
    recorded = ws[1].model_copy(update={"height": 12.5})
    assert recorded.box_height == 12.5


# ---------------------------------------------------------------- round 4: the garbled verdict and the precedence table


ALL_CASES = [  # (digits_ok, garbled_ok, leftover_ok, verdict)
    (True, True, True, "pass"),
    (True, True, False, "partial"),
    (True, False, True, "garbled"),
    (True, False, False, "garbled+partial"),
    (False, True, True, "digits-changed"),
    (False, True, False, "digits-changed+partial"),
    (False, False, True, "digits-changed+garbled"),
    (False, False, False, "digits-changed+garbled+partial"),
]


@pytest.mark.parametrize("digits_ok, garbled_ok, leftover_ok, verdict", ALL_CASES)
def test_verdict_names_for_every_combination(digits_ok, garbled_ok, leftover_ok, verdict):
    assert verify.verdict_for(digits_ok, leftover_ok, garbled_ok) == verdict
    assert verify.verdict_for(digits_ok, leftover_ok) == verify.verdict_for(digits_ok, leftover_ok, True)


def test_the_precedence_table_best_to_worst():
    assert verify.VERDICTS_BEST_TO_WORST == tuple(c[3] for c in ALL_CASES)
    ranks = [verify.VERDICT_RANK[v] for v in verify.VERDICTS_BEST_TO_WORST]
    assert ranks == sorted(ranks, reverse=True) and len(set(ranks)) == 8
    assert verify.VERDICT_UNVERIFIED not in verify.VERDICT_RANK
    for better, worse in itertools.combinations(verify.VERDICTS_BEST_TO_WORST, 2):
        assert verify.choose_attempt([v(worse), v(better)]) == 1, (better, worse)
        assert verify.choose_attempt([v(better), v(worse)]) == 0, (better, worse)


def test_the_two_invariants_hold_for_every_pair_of_verdicts():
    changed = lambda verdict: "digits-changed" in verdict  # noqa: E731
    garbled = lambda verdict: "garbled" in verdict  # noqa: E731
    for a, b in itertools.permutations(verify.VERDICTS_BEST_TO_WORST, 2):
        a_wins = verify.choose_attempt([v(b), v(a)]) == 1
        if changed(a) and not changed(b):
            assert not a_wins, f"{a} changed digits and must not beat {b}"
        if changed(a) == changed(b) and garbled(a) and not garbled(b):
            assert not a_wins, f"{a} has garbled text and must not beat {b}"


def test_garbled_text_with_good_digits_beats_changed_digits_with_clean_text():
    # the one pair where the two invariants pull apart: the digits rule wins (garbled > digits-changed)
    assert verify.choose_attempt([v("digits-changed", ["1234"]), v("garbled", coverage=0.5)]) == 1
    assert verify.choose_attempt([v("garbled+partial", coverage=0.5, ratio=0.9), v("digits-changed", ["1234"], coverage=1.0)]) == 0


def test_ties_prefer_coverage_then_fewer_missing_digits_then_lower_leftover_then_the_earlier_attempt():
    assert verify.choose_attempt([v("garbled", coverage=0.6), v("garbled", coverage=0.8)]) == 1
    assert verify.choose_attempt([v("pass", coverage=0.99), v("pass", coverage=0.95)]) == 0
    both = dict(coverage=0.9)
    assert verify.choose_attempt([v("digits-changed", ["1", "2"], **both), v("digits-changed", ["1"], **both)]) == 1
    assert verify.choose_attempt([v("partial", ratio=0.3, **both), v("partial", ratio=0.2, **both)]) == 1
    assert verify.choose_attempt([v("partial", ratio=0.2, **both), v("partial", ratio=0.2, **both)]) == 0
    # coverage outranks the digit and leftover tie-breaks
    assert verify.choose_attempt([v("partial", ratio=0.1, coverage=0.91), v("partial", ratio=0.5, coverage=0.99)]) == 1


def test_an_attempt_without_a_judge_ranks_below_a_judged_one_of_the_same_verdict():
    assert verify.choose_attempt([v("pass"), v("pass", coverage=0.9)]) == 1
    assert verify.choose_attempt([v("pass", coverage=0.9), v("pass")]) == 0
    assert verify.choose_attempt([v("pass"), v("pass")]) == 0
    assert verify.choose_attempt([None, v("garbled+partial")]) == 1


def test_check_garbled_follows_the_judge_record():
    ok = verify.check_garbled(judge_record(coverage=0.97))
    assert ok == {"garbled_ok": True, "coverage": 0.97, "garbled_count": 0, "judge_status": "ok"}
    assert verify.check_garbled(judge_record(coverage=0.90))["garbled_ok"] is True  # inclusive at 0.90
    assert verify.check_garbled(judge_record(coverage=0.8999))["garbled_ok"] is False
    segments = judge_record(coverage=1.0, garbled=[("Banero Salaino", "菜單")])
    assert verify.check_garbled(segments)["garbled_ok"] is False and verify.check_garbled(segments)["garbled_count"] == 1
    for record in (None, judge_record(status="unavailable", error="boom"), judge_record(status="skipped")):
        assert verify.check_garbled(record)["garbled_ok"] is True
        assert verify.check_garbled(record)["coverage"] is None
    assert verify.check_garbled(judge_record(status="unavailable"))["judge_status"] == "unavailable"


SRC = ocr(("TEL", "en"), ("77446716", "en"), ("営業時間", "ja"), ("メニュー", "ja"), ("ご飯", "ja"), ("定食", "ja"), size=(100, 100))
GOOD = ocr(("TEL", "en"), ("77446716", "en"), ("Hours", "en"), ("Menu", "en"), ("Rice", "en"), ("Set", "en"), size=(100, 100))
GIBBERISH = ocr(("TEL", "en"), ("77446716", "en"), ("Banero", "en"), ("Salaino", "en"), ("Az-infe", "en"), ("Rilchkan", "en"), size=(100, 100))


def test_gibberish_in_the_target_script_passes_the_ocr_checks_but_not_the_judge():
    assert verify.verify_output(SRC, GIBBERISH, "en")["verdict"] == "pass"  # round 3: nothing to see for OCR alone
    judged = judge_record(coverage=0.35, garbled=[("Banero Salaino Az-infe Rilchkan", "営業時間 メニュー ご飯 定食")])
    report = verify.verify_output(SRC, GIBBERISH, "en", judge=judged)
    assert report["verdict"] == "garbled" and report["coverage"] == 0.35 and report["garbled_ok"] is False
    assert report["garbled_count"] == 1 and report["judge"] is judged and report["digits_ok"] and report["leftover_ok"]


def test_low_coverage_alone_is_garbled_and_a_clean_judge_keeps_pass():
    assert verify.verify_output(SRC, GOOD, "en", judge=judge_record(coverage=0.89))["verdict"] == "garbled"
    report = verify.verify_output(SRC, GOOD, "en", judge=judge_record(coverage=0.97))
    assert report["verdict"] == "pass" and report["coverage"] == 0.97
    assert report["thresholds"]["coverage_min"] == 0.9


def test_an_unavailable_judge_leaves_the_verdict_to_the_ocr_checks():
    report = verify.verify_output(SRC, GOOD, "en", judge=judge_record(status="unavailable", error="no key"))
    assert report["verdict"] == "pass" and report["coverage"] is None and report["judge"]["status"] == "unavailable"
    assert verify.verify_output(SRC, GOOD, "en")["judge"] is None


def test_all_three_problems_together():
    bad = ocr(("TEL", "en"), ("09446916", "en"), ("営業時間", "ja"), ("Banero", "en"), size=(100, 100))
    report = verify.verify_output(SRC, bad, "en", judge=judge_record(coverage=0.3, garbled=[("Banero", "メニュー")]))
    assert report["verdict"] == "digits-changed+garbled+partial"
    garbled_partial = verify.verify_output(SRC, ocr(("TEL", "en"), ("77446716", "en"), ("営業時間", "ja"), ("Menu", "en"), ("Rice", "en"), ("Set", "en"), size=(100, 100)),
                                           "en", judge=judge_record(coverage=0.5))  # fmt: skip
    assert garbled_partial["verdict"] == "garbled+partial"
