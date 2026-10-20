"""Verification: the judge decides the verdict, the OCR digit / leftover checks are warnings only."""

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


def test_a_soft_miss_is_a_warning_and_a_hard_miss_a_stronger_one_but_neither_touches_the_verdict():
    source = ocr(("TEL", "en", 0.99), ("77446716", "en", 0.5), ("Total", "en", 0.99), ("1,200", "en", 0.95), size=(100, 100))
    soft_gone = ocr(("TEL", "en"), ("09446916", "en"), ("Total", "en"), ("1,200", "en"), size=(100, 100))
    report = verify.verify_output(source, soft_gone, "en", judge=judge_record())
    assert report["verdict"] == "pass" and report["ocr_warnings"]["unverified_digit_runs"] == ["77446716"]
    assert report["ocr_warnings"]["warnings"] == ["digits-unverified"]
    assert report["thresholds"]["soft_confidence_max"] == 0.75 and report["thresholds"]["tolerant_min_digits"] == 5
    hard_gone = ocr(("TEL", "en"), ("77446716", "en"), ("Total", "en"), ("1,300", "en"), size=(100, 100))
    report = verify.verify_output(source, hard_gone, "en", judge=judge_record())
    assert report["ocr_warnings"]["missing_digit_runs"] == ["1200"] and report["ocr_warnings"]["warnings"] == ["digits-missing"]
    assert report["verdict"] == "pass"  # round 5: the OCR digit check is a warning, the judge decides


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


def leftover(source, out, lang="en"):
    return verify.check_leftover(source.words, out.words, lang, verify.detect_source_language(source.words)[0])


def test_a_leftover_word_more_than_twice_the_median_height_fails_the_leftover_check_whatever_the_ratio():
    # round 3: the second line of a poster title stayed Vietnamese, 3 words of 260 (0.0115): the ratio did not see it
    source, out = title_case(26)  # 2.6 x the median 10
    result = leftover(source, out)
    assert result["leftover_ratio"] == pytest.approx(1 / 300, abs=1e-4) and result["leftover_ratio_ok"] is True
    assert result["leftover_ok"] is False
    assert result["big_leftover_words"] == [{"word": "メニュー", "height": 26.0, "median_height": 10.0, "ratio": 2.6}]
    assert result["median_word_height"] == 10.0
    # round 5: the same finding is only an OCR warning; the verdict comes from the judge
    report = verify.verify_output(source, out, "en", judge=judge_record())
    assert report["verdict"] == "pass" and report["ocr_warnings"]["warnings"] == ["leftover-big-glyph"]
    assert report["thresholds"]["big_glyph_ratio"] == 2.0


def test_exactly_twice_the_median_is_not_big_and_a_big_translated_word_is_fine():
    source, out = title_case(20)
    assert leftover(source, out)["leftover_ok"] is True and leftover(source, out)["big_leftover_words"] == []
    # a big English title (translated) next to small body text is the normal case
    source = ocr(*[("こんにちは", "ja")] * 20, size=(100, 100))
    out = ocr(*[("Hello", "en")] * 19, ("MENU", "en", 0.9, 60), size=(100, 100))
    assert leftover(source, out)["leftover_ok"] is True


def test_the_median_is_the_median_of_all_output_words_and_the_word_is_listed_once():
    source = ocr(*[("こんにちは", "ja")] * 100, size=(100, 100))
    # most words of this output are tall (25): a 40 px leftover is only 1.6 x the median
    tall = ocr(*[("Hello", "en", 0.9, 25)] * 60, *[("World", "en")] * 39, ("メニュー", "ja", 0.9, 40), size=(100, 100))
    assert leftover(source, tall)["big_leftover_words"] == []
    # the same big word printed twice is one entry
    out = ocr(*[("Hello", "en")] * 90, ("メニュー", "ja", 0.9, 40), ("メニュー", "ja", 0.9, 40), size=(100, 100))
    result = leftover(source, out)
    assert [b["word"] for b in result["big_leftover_words"]] == ["メニュー"] and result["leftover_ok"] is False


def test_without_recorded_heights_the_polygon_is_used():
    ws = ocr(("Hello", "en"), ("メニュー", "ja", 0.9, 40)).words
    assert [w.height for w in ws] == [0.0, 0.0]  # never recorded by Vision here
    assert [w.box_height for w in ws] == [10.0, 40.0]  # computed from the polygon
    recorded = ws[1].model_copy(update={"height": 12.5})
    assert recorded.box_height == 12.5


# ---------------------------------------------------------------- the verdict comes from the judge

CHECKS = ("numbers", "rows", "garbled", "partial", "score")


def test_the_check_table_and_the_threshold():
    assert verify.CHECKS == CHECKS and verify.USABILITY_MIN == 0.80
    assert verify.ROW_PROBLEMS == {"duplicated_row", "missing_row", "misaligned_marks"}


def test_a_clean_judge_is_a_pass_with_all_five_checks_ok():
    report = verify.verify_output(SRC, GOOD, "en", judge=judge_record(usability=0.97, coverage=0.93))
    assert report["verdict"] == "pass" and report["failed_checks"] == []
    assert report["usability"] == 0.97 and report["coverage"] == 0.93
    assert [(n, report["checks"][n]["ok"]) for n in CHECKS] == [(n, True) for n in CHECKS]
    assert report["checks"]["score"] == {"ok": True, "usability": 0.97, "min": 0.80}
    assert report["thresholds"]["usability_min"] == 0.80 and report["target_language"] == "en"


def test_numbers_fail_when_the_judge_lists_an_altered_number():
    report = verify.verify_output(SRC, GOOD, "en", judge=judge_record(altered=[("77446716", "99446916")]))
    assert report["verdict"] == "numbers" and report["failed_checks"] == ["numbers"]
    assert report["checks"]["numbers"] == {"ok": False, "count": 1}


@pytest.mark.parametrize(
    "kind, fails",
    [("duplicated_row", True), ("missing_row", True), ("misaligned_marks", True), ("shifted", False), ("other", False)],
)
def test_rows_fail_for_duplicated_missing_or_misaligned_rows_only(kind, fails):
    report = verify.verify_output(SRC, GOOD, "en", judge=judge_record(rows=[(kind, "the third row")]))
    assert report["verdict"] == ("rows" if fails else "pass")
    assert report["checks"]["rows"]["count"] == (1 if fails else 0)


def test_garbled_needs_a_major_legibility_issue_and_partial_a_major_untranslated_text():
    minor = judge_record(garbled=[("logo lettering", "Sinoe 2024", False)], untranslated=[("footnote", False)])
    assert verify.verify_output(SRC, GOOD, "en", judge=minor)["verdict"] == "pass"  # minor issues never fail a verdict
    major = judge_record(garbled=[("price pill", "Manbar Price", True)])
    assert verify.verify_output(SRC, GOOD, "en", judge=major)["verdict"] == "garbled"
    assert verify.verify_output(SRC, GOOD, "en", judge=judge_record(untranslated=["ご飯"]))["verdict"] == "partial"
    only_mistranslated = judge_record(mistranslated=[("Perm", "漂髮")])
    assert verify.verify_output(SRC, GOOD, "en", judge=only_mistranslated)["verdict"] == "pass"


@pytest.mark.parametrize("usability, verdict", [(0.80, "pass"), (0.7999, "score"), (0.0, "score"), (1.0, "pass"), (0.95, "pass")])
def test_score_fails_below_the_usability_threshold_and_the_threshold_itself_passes(usability, verdict):
    assert verify.verify_output(SRC, GOOD, "en", judge=judge_record(usability=usability))["verdict"] == verdict


def test_coverage_alone_never_decides():
    report = verify.verify_output(SRC, GOOD, "en", judge=judge_record(usability=0.95, coverage=0.1))
    assert report["verdict"] == "pass" and report["coverage"] == 0.1


@pytest.mark.parametrize("n", range(1, 32))
def test_every_combination_of_failures_is_named_in_the_order_numbers_rows_garbled_partial_score(n):
    failing = [name for i, name in enumerate(CHECKS) if n >> i & 1]
    judge = judge_record(
        usability=0.5 if "score" in failing else 0.95,
        altered=[("1", "2")] if "numbers" in failing else (),
        rows=[("duplicated_row", "x")] if "rows" in failing else (),
        garbled=["xyz"] if "garbled" in failing else (),
        untranslated=["abc"] if "partial" in failing else (),
    )
    report = verify.verify_output(SRC, GOOD, "en", judge=judge)
    assert report["verdict"] == "+".join(failing) and report["failed_checks"] == failing
    assert verify.verdict_for(reversed(failing)) == "+".join(failing)  # the order is fixed, not the input's


def test_verdict_for_names_and_rejects_unknown_checks():
    assert verify.verdict_for([]) == "pass" and verify.verdict_for({"score"}) == "score"
    assert verify.verdict_for(["partial", "numbers"]) == "numbers+partial"
    with pytest.raises(ValueError):
        verify.verdict_for(["digits-changed"])


@pytest.mark.parametrize(
    "record",
    [judge_record(status="unavailable", error="no key"), judge_record(status="skipped"), None],
)
def test_without_a_judge_answer_the_verdict_is_unverified_but_the_ocr_warnings_are_still_there(record):
    report = verify.verify_output(SRC, ocr(("TEL", "en"), ("09446916", "en"), size=(100, 100)), "en", judge=record)
    assert report["verdict"] == "unverified" and report["checks"] is None and report["usability"] is None
    assert report["failed_checks"] == [] and report["coverage"] is None
    assert report["ocr_warnings"]["status"] == "ok" and "digits-missing" in report["ocr_warnings"]["warnings"]
    assert not verify.is_verified(report["verdict"]) and not verify.needs_retry(report["verdict"])


def test_needs_retry_is_a_reached_verdict_that_is_not_pass():
    assert verify.needs_retry("score") and verify.needs_retry("numbers+garbled")
    assert not verify.needs_retry("pass") and not verify.needs_retry("unverified") and not verify.needs_retry(None)


def test_the_ocr_checks_are_warnings_only_and_never_touch_the_verdict():
    # digits changed AND the title left in Japanese: round 4 said digits-changed+partial; now the judge says pass
    bad = ocr(("TEL", "en"), ("09446916", "en"), ("営業時間", "ja"), ("Menu", "en"), ("Rice", "en"), ("Set", "en"), size=(100, 100))
    report = verify.verify_output(SRC, bad, "en", judge=judge_record(usability=0.93))
    assert report["verdict"] == "pass" and report["failed_checks"] == []
    warnings = report["ocr_warnings"]
    assert warnings["status"] == "ok" and warnings["warnings"] == ["digits-missing", "leftover-ratio"]
    assert warnings["missing_digit_runs"] == ["77446716"] and warnings["leftover_words"] == ["営業時間"]
    assert warnings["source_language"] == "ja" and warnings["source_language_method"] == "detected"
    assert "output_text" not in warnings  # only with include_text
    assert verify.verify_output(SRC, bad, "en", include_text=True, judge=judge_record())["ocr_warnings"]["output_text"].startswith("TEL 09446916")


def test_a_failed_vision_costs_only_the_warnings():
    report = verify.verify_output(None, None, "en", judge=judge_record(usability=0.9), ocr_error="Google Vision credentials are not usable")
    assert report["verdict"] == "pass" and report["usability"] == 0.9
    assert report["ocr_warnings"] == {"status": "unavailable", "error": "Google Vision credentials are not usable", "warnings": []}


def test_an_empty_source_has_nothing_to_compare_and_says_so():
    report = verify.verify_output(ocr(size=(100, 100)), ocr(("Hello", "en"), size=(100, 100)), "en", judge=judge_record())
    assert report["verdict"] == "pass" and "nothing to compare" in report["note"]
    assert report["ocr_warnings"]["source_language"] is None and report["ocr_warnings"]["source_digit_runs"] == 0


def test_the_output_digits_come_from_the_words_when_vision_gave_no_full_text():
    source = ocr(("TEL", "en"), ("77446716", "en"), size=(100, 100))
    no_text = ocr(("TEL", "en"), ("09446916", "en"), text="", size=(100, 100))
    assert verify.verify_output(source, no_text, "en")["ocr_warnings"]["missing_digit_runs"] == ["77446716"]
    kept = ocr(("TEL", "en"), ("77446716", "en"), text="", size=(100, 100))
    report = verify.verify_output(source, kept, "en", include_text=True)
    assert report["ocr_warnings"]["warnings"] == [] and report["ocr_warnings"]["output_text"] == "TEL 77446716"


SRC = ocr(("TEL", "en"), ("77446716", "en"), ("営業時間", "ja"), ("メニュー", "ja"), ("ご飯", "ja"), ("定食", "ja"), size=(100, 100))
GOOD = ocr(("TEL", "en"), ("77446716", "en"), ("Hours", "en"), ("Menu", "en"), ("Rice", "en"), ("Set", "en"), size=(100, 100))


# ---------------------------------------------------------------- picking between attempts


def v(verdict, usability=None, coverage=None):
    return {"verdict": verdict, "usability": usability, "coverage": coverage}


ALL_VERDICTS = ["pass"] + ["+".join(n for i, n in enumerate(CHECKS) if m >> i & 1) for m in range(1, 32)]


def flags(verdict):
    failed = set() if verdict == "pass" else set(verdict.split("+"))
    return tuple(name not in failed for name in CHECKS)


def test_verdict_flags_and_the_order_of_a_verdict():
    assert verify.verdict_flags("pass") == (True,) * 5
    assert verify.verdict_flags("numbers+score") == (False, True, True, True, False)
    for junk in ("unverified", None, "", "digits-changed", "garbled+nonsense", 5):
        assert verify.verdict_flags(junk) is None


def test_precedence_over_every_pair_of_verdicts_is_lexicographic_on_the_five_checks():
    assert len(ALL_VERDICTS) == 32
    for a in ALL_VERDICTS:
        for b in ALL_VERDICTS:
            if a == b:
                continue
            # the checks are compared in the order numbers, rows, garbled, partial, score; a fully ok check wins
            expected_a_wins = flags(a) > flags(b)
            assert verify.choose_attempt([v(b), v(a)]) == (1 if expected_a_wins else 0), (a, b)


def test_the_brief_s_examples_of_the_order():
    # a changed number is worse than anything else
    assert verify.choose_attempt([v("numbers"), v("rows+garbled+partial+score")]) == 1
    # rows come before garbled, which comes before partial, which comes before score
    assert verify.choose_attempt([v("rows"), v("garbled+partial+score")]) == 1
    assert verify.choose_attempt([v("garbled"), v("partial+score")]) == 1
    assert verify.choose_attempt([v("partial"), v("score")]) == 1
    assert verify.choose_attempt([v("pass", 0.8), v("score", 0.99)]) == 0


def test_ties_prefer_usability_then_coverage_then_the_earlier_attempt():
    assert verify.choose_attempt([v("score", 0.6), v("score", 0.7)]) == 1
    assert verify.choose_attempt([v("pass", 0.99), v("pass", 0.95)]) == 0
    assert verify.choose_attempt([v("pass", 0.9, 0.7), v("pass", 0.9, 0.95)]) == 1
    assert verify.choose_attempt([v("pass", 0.9, 0.95), v("pass", 0.9, 0.7)]) == 0
    assert verify.choose_attempt([v("pass", 0.9, 0.9), v("pass", 0.9, 0.9)]) == 0  # the earlier attempt keeps ties
    assert verify.choose_attempt([v("garbled", 0.9), v("garbled", 0.9), v("garbled", 0.9)]) == 0
    # usability outranks coverage; an unknown value counts as below any known one
    assert verify.choose_attempt([v("pass", 0.85, 0.99), v("pass", 0.9, 0.5)]) == 1
    assert verify.choose_attempt([v("pass", None, 0.5), v("pass", 0.1, None)]) == 1


def test_the_check_order_beats_usability():
    assert verify.choose_attempt([v("partial", 0.7), v("numbers", 0.99)]) == 0  # more usable, but a changed number
    assert verify.choose_attempt([v("numbers", 0.99), v("partial", 0.1)]) == 1


def test_an_unverified_attempt_never_replaces_a_verified_one():
    assert verify.choose_attempt([v("numbers+rows+garbled+partial+score", 0.0), None]) == 0
    assert verify.choose_attempt([None, v("score", 0.5)]) == 1
    assert verify.choose_attempt([{"verdict": "unverified"}, v("pass")]) == 1
    assert verify.choose_attempt([v("pass"), {"verdict": "unverified"}]) == 0
    assert verify.choose_attempt([None]) == 0 and verify.choose_attempt([None, None]) == 0


def test_the_attempt_key_ranks_verified_attempts_above_unverified_ones():
    assert verify.attempt_key(None) == verify.attempt_key({"verdict": "unverified"}) == (0,)
    assert verify.attempt_key(v("pass", 0.9, 0.8)) == (1, True, True, True, True, True, 0.9, 0.8)
