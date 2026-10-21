"""Mode B / Mode A verification: the judge decides, the OCR checks only warn.

Round 4 judged from OCR text and failed on real images (round 5 brief): Vision misreads tiny
rendered text and handwriting, so correct pictures were flagged, while errors that need a look at
both pictures (a duplicated table row, a mistranslated word, a changed phone number) went
unreported. The verdict now comes from the vision judge (judge.py), which sees the original and the
output:

====================  ====================================================================
check                 fails when
====================  ====================================================================
``numbers``           the judge lists any ``altered_numbers``
``rows``              any ``structure_issues`` of type duplicated_row, missing_row or
                      misaligned_marks that has a non-empty ``detail`` (it names the row);
                      Mode B only: Mode A draws its text in place, so its rows cannot
                      change and the check is off for it (``structure_checks=False``)
``garbled``           any ``legibility_issues`` with ``major: true``
``partial``           any ``untranslated`` with ``major: true``
``score``             ``usability`` is below USABILITY_MIN (0.80)
====================  ====================================================================

The verdict is ``pass`` when nothing fails, else the failed checks joined with ``+`` in the order
above (``numbers+garbled``), and ``unverified`` when the judge is unavailable or skipped (below
everything else). Attempts are ranked lexicographically on (numbers ok, rows ok, garbled ok,
partial ok, score ok), then higher usability, then higher coverage, then the earlier attempt.

With ``structure_checks=False`` (Mode A, round 7) the judge's structure issues are kept in the report and
listed as ``structure_warnings`` but never fail ``rows``: the round-5 and round-6 ``misaligned_marks`` and
``missing_row`` entries on Mode A pictures were judge noise.

The round-4 OCR checks stay computed and stored, but only as ``ocr_warnings`` (they no longer touch
the verdict or the retry prompt):

* digit integrity - every run of 2+ digits of the source must still be in the output; a run read
  only from low-confidence words (handwriting) is *soft* and, for 5+ digits, matches within one digit;
* leftover source language - the share of source-language words that survive in the output (at most
  LEFTOVER_RATIO_MAX), and no leftover word much bigger than the rest of the output.

Pure functions, no network: the OCR results and the judge record come in as plain objects.
"""

from __future__ import annotations

import re
import statistics
import unicodedata
from collections import Counter

from .models import OcrResult, Word, base_language

LEFTOVER_RATIO_MAX = 0.08
MIN_RUN_DIGITS = 2
MAX_LEFTOVER_LISTED = 20
SEPARATORS = "-:/.,"
SOFT_CONFIDENCE_MAX = 0.75  # a word below this is "soft" (handwriting, small print)
TOLERANT_MIN_DIGITS = 5  # soft runs of at least this many digits match within one digit
BIG_GLYPH_RATIO = 2.0  # a leftover word taller than this x the median word height is a warning of its own
USABILITY_MIN = 0.80  # judge usability below this fails the `score` check (0.80 itself passes)

# The checks in order of severity; a verdict is `pass` or the failed ones joined with "+".
CHECKS = ("numbers", "rows", "garbled", "partial", "score")
ROW_PROBLEMS = frozenset({"duplicated_row", "missing_row", "misaligned_marks"})  # `shifted` and `other` never fail

VERDICT_PASS = "pass"
VERDICT_UNVERIFIED = "unverified"  # the judge could not say (no key, API error, no output image, judge off)

_RUN_RE = re.compile(r"[0-9](?:[" + re.escape(SEPARATORS) + r"]?[0-9])*")
_URLISH_RE = re.compile(
    r"://|^www\.|@|\.(?:com|net|org|edu|gov|info|io|co|jp|tw|cn|kr|vn|hk|de|uk|us)(?:[/:?]|$)", re.I
)

# scripts (see char_script) in which each lab target language is written
TARGET_SCRIPTS: dict[str, frozenset[str]] = {
    "en": frozenset({"Latin"}),
    "vi": frozenset({"Latin", "Latin-vi"}),
    "zh": frozenset({"Han", "Bopomofo"}),
    "ja": frozenset({"Han", "Kana"}),
    "ko": frozenset({"Hangul"}),
    "th": frozenset({"Thai"}),
    "ar": frozenset({"Arabic"}),
    "he": frozenset({"Hebrew"}),
    "hi": frozenset({"Devanagari"}),
    "ru": frozenset({"Cyrillic"}),
    "uk": frozenset({"Cyrillic"}),
    **{code: frozenset({"Latin"}) for code in ("id", "ms", "tl", "fr", "de", "es", "it", "pt", "tr", "nl", "pl")},
}


# ---------------------------------------------------------------- text helpers


def _fold(text: str) -> str:
    """NFKC (full-width digits, separators and Latin become ASCII) and every decimal digit to 0-9."""
    text = unicodedata.normalize("NFKC", text)
    return "".join(
        str(unicodedata.decimal(ch)) if ch.isdecimal() and not ch.isascii() else ch for ch in text
    )


def extract_digit_runs(text: str) -> list[tuple[str, str]]:
    """[(normalised run, run as written)] for every run of 2+ digits, in reading order."""
    out = []
    for match in _RUN_RE.finditer(_fold(text)):
        raw = match.group(0)
        digits = "".join(ch for ch in raw if ch.isdigit())
        if len(digits) >= MIN_RUN_DIGITS:
            out.append((digits, raw))
    return out


def digit_string(text: str) -> str:
    """Every digit of the text, concatenated (the output side of the digit check)."""
    return "".join(ch for ch in _fold(text) if "0" <= ch <= "9")


# ---------------------------------------------------------------- scripts and languages

_VI_UNIQUE = frozenset("ăĂđĐơƠưƯ")
_HAN = ((0x4E00, 0x9FFF), (0x3400, 0x4DBF), (0x20000, 0x2FA1F), (0xF900, 0xFAFF), (0x3005, 0x3005), (0x3007, 0x3007))
_KANA = ((0x3040, 0x30FF), (0x31F0, 0x31FF), (0xFF66, 0xFF9F), (0x1B000, 0x1B16F))
_HANGUL = ((0xAC00, 0xD7AF), (0x1100, 0x11FF), (0x3130, 0x318F), (0xA960, 0xA97F), (0xD7B0, 0xD7FF))
_BOPOMOFO = ((0x3100, 0x312F), (0x31A0, 0x31BF))
_RANGES = (("Han", _HAN), ("Kana", _KANA), ("Hangul", _HANGUL), ("Bopomofo", _BOPOMOFO))


def char_script(ch: str) -> str | None:
    """Script of a letter (None for digits, punctuation, symbols and spaces).

    Han / Kana / Hangul / Bopomofo by range, Latin split into 'Latin-vi' (letters only
    Vietnamese uses: ă đ ơ ư and the Latin Extended Additional block) and 'Latin', anything
    else by its Unicode name (Cyrillic, Thai, Arabic ...).
    """
    cp = ord(ch)
    for script, ranges in _RANGES:
        if any(lo <= cp <= hi for lo, hi in ranges):
            return script if (ch.isalpha() or cp == 0x3007) else None
    if not ch.isalpha():
        return None
    try:
        first = unicodedata.name(ch).split()[0]
    except ValueError:
        return None
    if first == "LATIN":
        return "Latin-vi" if ch in _VI_UNIQUE or 0x1EA0 <= cp <= 0x1EF9 else "Latin"
    return first.title()


def word_scripts(text: str) -> set[str]:
    return {s for s in (char_script(ch) for ch in unicodedata.normalize("NFKC", text)) if s}


def _core(text: str) -> str:
    """The word without surrounding punctuation / symbols / spaces."""
    text = unicodedata.normalize("NFKC", text).strip()
    start, end = 0, len(text)
    while start < end and not text[start].isalnum():
        start += 1
    while end > start and not text[end - 1].isalnum():
        end -= 1
    return text[start:end]


def is_countable(text: str) -> bool:
    """A word that can say something about a language: not punctuation, not digit-only, not 1 char."""
    core = _core(text)
    return len(core) >= 2 and any(ch.isalpha() for ch in core)


def _script_language(scripts: set[str], source_lang: str | None = None) -> str | None:
    """Language implied by the scripts of one word (fallback when Vision named none)."""
    if "Kana" in scripts:
        return "ja"
    if "Hangul" in scripts:
        return "ko"
    if "Han" in scripts or "Bopomofo" in scripts:
        return source_lang if source_lang in ("zh", "ja") else "zh"
    if "Latin-vi" in scripts:
        return "vi"
    if "Latin" in scripts:
        return "en"
    return None


def word_language(word: Word, source_lang: str | None = None) -> str | None:
    """Detected language of a word, or what its script implies when Vision gave none."""
    detected = base_language(word.lang)
    if detected:
        return detected
    return _script_language(word_scripts(word.text), source_lang)


def detect_source_language(words: list[Word]) -> tuple[str | None, str]:
    """(language, method): the majority of the words' detected languages, else the scripts.

    Only words that can carry a language (is_countable) vote. Japanese and Chinese share the
    Han script and Vision often tags a kanji-only word 'zh' in a Japanese text, so when the
    vote says 'zh' but at least 10% of the CJK words contain kana the text is Japanese.
    """
    voters = [w for w in words if is_countable(w.text)]
    votes = Counter(base_language(w.lang) for w in voters if base_language(w.lang))
    kana = sum(1 for w in voters if "Kana" in word_scripts(w.text))
    cjk = sum(1 for w in voters if word_scripts(w.text) & {"Han", "Kana"})
    if votes:
        lang = votes.most_common(1)[0][0]
        if lang == "zh" and kana and kana >= 0.1 * cjk:
            lang = "ja"
        return lang, "detected"

    scripts = [word_scripts(w.text) for w in voters]
    hangul = sum(1 for s in scripts if "Hangul" in s)
    han = sum(1 for s in scripts if "Han" in s and "Kana" not in s and "Hangul" not in s)
    latin = sum(1 for s in scripts if s and s <= {"Latin", "Latin-vi"})
    vi = sum(1 for s in scripts if "Latin-vi" in s)
    candidates: dict[str, int] = {}
    if kana:
        candidates["ja"] = kana + han
    elif han:
        candidates["zh"] = han
    if hangul:
        candidates["ko"] = hangul
    if latin:
        candidates["vi" if vi >= 0.15 * latin else "en"] = latin
    if candidates:
        return max(candidates, key=candidates.get), "script"
    return None, "none"


def _same_language(word_lang: str | None, source_lang: str, scripts: set[str]) -> bool:
    if word_lang == source_lang:
        return True
    # a Han-only word is Chinese and Japanese at once: Vision's pick between the two is a coin toss
    return (
        source_lang in ("zh", "ja")
        and word_lang in ("zh", "ja")
        and "Han" in scripts
        and not scripts & {"Kana", "Hangul", "Latin", "Latin-vi"}
    )


# ---------------------------------------------------------------- the checks


def _near_run(digits: str, output_runs: list[str]) -> str | None:
    """An output run of the same length that differs from `digits` in at most one digit."""
    for candidate in output_runs:
        if len(candidate) == len(digits) and sum(a != b for a, b in zip(digits, candidate)) <= 1:
            return candidate
    return None


def check_digits(source_words: list[Word], output_text: str) -> dict:
    """Digit integrity of the output against the source, hard runs strict and soft runs tolerant.

    Every distinct 2+ digit run of the source is looked up in the output's digits. A run's
    confidence is the best confidence of the words it was read from (a confidence of 0, which
    Vision uses for "not reported", counts as hard). Statuses per run:

    * ``ok``         - a substring of the output's digit string;
    * ``near``       - soft, 5+ digits, not a substring, but an output run of the same length is
                       one digit away (the source OCR misread the handwriting, or the output did);
    * ``unverified`` - soft and not found: shown as a warning, never fails the verdict;
    * ``missing``    - hard and not found: fails the verdict (``digits_ok`` False).
    """
    runs: dict[str, dict] = {}  # normalised run -> {as_written (first), confidence (best)}
    for w in source_words:
        confidence = float(w.confidence or 0.0)
        for digits, raw in extract_digit_runs(w.text):
            info = runs.get(digits)
            if info is None:
                runs[digits] = {"as_written": raw, "confidence": confidence}
            else:
                info["confidence"] = max(info["confidence"], confidence)

    out = digit_string(output_text)
    output_runs = [digits for digits, _ in extract_digit_runs(output_text)]
    detail: list[dict] = []
    missing: list[dict] = []
    unverified: list[dict] = []
    for digits, info in runs.items():
        confidence = info["confidence"]
        soft = 0.0 < confidence < SOFT_CONFIDENCE_MAX
        status, matched = "ok", None
        if digits not in out:
            near = _near_run(digits, output_runs) if soft and len(digits) >= TOLERANT_MIN_DIGITS else None
            if near is not None:
                status, matched = "near", near
            elif soft:
                status = "unverified"
            else:
                status = "missing"
        entry = {
            "run": digits,
            "as_written": info["as_written"],
            "confidence": round(confidence, 3),
            "soft": soft,
            "status": status,
        }
        if matched is not None:
            entry["matched"] = matched
        detail.append(entry)
        if status == "missing":
            missing.append({"run": digits, "as_written": info["as_written"]})
        elif status == "unverified":
            unverified.append({"run": digits, "as_written": info["as_written"], "confidence": round(confidence, 3)})
    return {
        "digits_ok": not missing,
        "missing_digit_runs": [d["run"] for d in missing],
        "missing_digit_details": missing,
        "unverified_digit_runs": [d["run"] for d in unverified],
        "unverified_digit_details": unverified,
        "digit_runs": detail,
        "source_digit_runs": len(runs),
        "soft_digit_runs": sum(1 for d in detail if d["soft"]),
    }


def _target_base(target_lang: str) -> str:
    return base_language(target_lang) or target_lang.lower()


def check_leftover(
    source_words: list[Word], output_words: list[Word], target_lang: str, source_lang: str | None
) -> dict:
    """Share of source-language words that are still there in the output, and how big they are.

    Counted on both sides: words that can carry a language (is_countable), and in the output
    additionally not URLs / e-mail addresses and not written entirely in a script of the target
    language (a kanji-only word in a Chinese output proves nothing).

    ``leftover_ok`` is False when the ratio is above LEFTOVER_RATIO_MAX *or* when a leftover word
    is taller than BIG_GLYPH_RATIO x the median word height of the output (a leftover title
    matters whatever the ratio is); ``big_leftover_words`` names those words.
    """
    target = _target_base(target_lang)
    target_scripts = TARGET_SCRIPTS.get(target, frozenset())
    if source_lang is None:
        return {
            "leftover_ok": True,
            "leftover_ratio_ok": True,
            "leftover_ratio": 0.0,
            "leftover_count": 0,
            "leftover_words": [],
            "big_leftover_words": [],
            "median_word_height": 0.0,
            "source_language_words": 0,
        }
    denominator = 0
    for w in source_words:
        if is_countable(w.text) and _same_language(word_language(w, source_lang), source_lang, word_scripts(w.text)):
            denominator += 1
    heights = [h for h in (w.box_height for w in output_words) if h > 0]
    median_height = float(statistics.median(heights)) if heights else 0.0
    leftover: list[str] = []
    big: dict[str, dict] = {}
    for w in output_words:
        if not is_countable(w.text) or _URLISH_RE.search(w.text):
            continue
        scripts = word_scripts(w.text)
        if not scripts or scripts <= target_scripts:
            continue
        if _same_language(word_language(w, source_lang), source_lang, scripts):
            core = _core(w.text)
            leftover.append(core)
            height = w.box_height
            if median_height > 0 and height > BIG_GLYPH_RATIO * median_height and core not in big:
                big[core] = {
                    "word": core,
                    "height": round(height, 1),
                    "median_height": round(median_height, 1),
                    "ratio": round(height / median_height, 2),
                }
    ratio = (len(leftover) / denominator) if denominator else 0.0
    listed = list(dict.fromkeys(leftover))[:MAX_LEFTOVER_LISTED]
    ratio_ok = ratio <= LEFTOVER_RATIO_MAX
    return {
        "leftover_ok": ratio_ok and not big,
        "leftover_ratio_ok": ratio_ok,
        "leftover_ratio": round(ratio, 4),
        "leftover_count": len(leftover),
        "leftover_words": listed,
        "big_leftover_words": list(big.values()),
        "median_word_height": round(median_height, 1),
        "source_language_words": denominator,
    }


# ---------------------------------------------------------------- the verdict


def _items(judge: dict, key: str) -> list[dict]:
    return [i for i in judge.get(key) or [] if isinstance(i, dict)]


def judge_checks(judge: dict | None, structure_checks: bool = True) -> dict | None:
    """The five checks from the judge record; None when the judge gave no answer.

    Each entry is ``{"ok": bool, "count": n}`` (``score`` carries the usability and the threshold). With
    ``structure_checks`` False the ``rows`` check never fails: its ``count`` is 0 and ``ignored`` says how many
    row problems the judge named anyway.
    """
    if not judge or judge.get("status") != "ok":
        return None
    altered = _items(judge, "altered_numbers")
    # an entry that names no row is not a finding (round 6): the judge prompt, not this filter, keeps
    # "nothing is wrong" statements out of the list; the detail text itself is never parsed
    named = [
        i for i in _items(judge, "structure_issues")
        if i.get("type") in ROW_PROBLEMS and str(i.get("detail") or "").strip()
    ]  # fmt: skip
    rows = named if structure_checks else []
    garbled = [i for i in _items(judge, "legibility_issues") if i.get("major")]
    partial = [i for i in _items(judge, "untranslated") if i.get("major")]
    raw = judge.get("usability")
    usability = float(raw) if isinstance(raw, (int, float)) and not isinstance(raw, bool) else 0.0
    return {
        "numbers": {"ok": not altered, "count": len(altered)},
        "rows": {"ok": not rows, "count": len(rows), **({} if structure_checks else {"ignored": len(named)})},
        "garbled": {"ok": not garbled, "count": len(garbled)},
        "partial": {"ok": not partial, "count": len(partial)},
        "score": {"ok": usability >= USABILITY_MIN, "usability": round(usability, 4), "min": USABILITY_MIN},
    }


def verdict_for(failed) -> str:
    """`pass`, or the failed checks joined with `+` in the order numbers, rows, garbled, partial, score."""
    failed = set(failed)
    unknown = failed - set(CHECKS)
    if unknown:
        raise ValueError(f"unknown check(s): {sorted(unknown)}")
    problems = [name for name in CHECKS if name in failed]
    return "+".join(problems) if problems else VERDICT_PASS


def verdict_flags(verdict: str | None) -> tuple[bool, ...] | None:
    """(numbers ok, rows ok, garbled ok, partial ok, score ok) of a verdict; None for `unverified` or junk."""
    if verdict == VERDICT_PASS:
        return (True,) * len(CHECKS)
    if not isinstance(verdict, str) or not verdict:
        return None
    failed = verdict.split("+")
    if any(name not in CHECKS for name in failed):
        return None
    return tuple(name not in failed for name in CHECKS)


def is_verified(verdict: str | None) -> bool:
    return verdict_flags(verdict) is not None


def needs_retry(verdict: str | None) -> bool:
    """A verdict that was actually reached and is not `pass` (an unverified attempt is never retried)."""
    return is_verified(verdict) and verdict != VERDICT_PASS


def _ocr_warnings(source: OcrResult, output: OcrResult, target_lang: str) -> dict:
    output_text = output.text or " ".join(w.text for w in output.words)
    digits = check_digits(source.words, output_text)
    source_lang, method = detect_source_language(source.words)
    leftover = check_leftover(source.words, output.words, target_lang, source_lang)
    codes = []
    if not digits["digits_ok"]:
        codes.append("digits-missing")
    if digits["unverified_digit_runs"]:
        codes.append("digits-unverified")
    if not leftover["leftover_ratio_ok"]:
        codes.append("leftover-ratio")
    if leftover["big_leftover_words"]:
        codes.append("leftover-big-glyph")
    return {
        "status": "ok",
        "warnings": codes,
        **digits,
        **leftover,
        "source_language": source_lang,
        "source_language_method": method,
        "source_word_count": len(source.words),
        "output_word_count": len(output.words),
        "output_text": output_text,
    }


def verify_output(
    source: OcrResult | None,
    output: OcrResult | None,
    target_lang: str,
    include_text: bool = False,
    judge: dict | None = None,
    ocr_error: str | None = None,
    structure_checks: bool = True,
) -> dict:
    """The verdict from the judge record, plus the OCR checks as warnings.

    `judge` is the record of judge.judge_output (None when the judge is off). `source` / `output`
    are the Vision results; when Vision failed they are None and `ocr_error` says why: the verdict
    does not depend on them, so the warnings are just missing. `structure_checks` False (Mode A) takes
    the `rows` check out of the verdict; the structure issues are then only listed as `structure_warnings`.
    """
    checks = judge_checks(judge, structure_checks)
    if checks is None:
        verdict, failed, usability, coverage = VERDICT_UNVERIFIED, [], None, None
    else:
        failed = [name for name in CHECKS if not checks[name]["ok"]]
        verdict = verdict_for(failed)
        usability, coverage = checks["score"]["usability"], judge.get("coverage")
    if source is not None and output is not None:
        warnings = _ocr_warnings(source, output, target_lang)
        if not include_text:
            warnings.pop("output_text", None)
    else:
        warnings = {"status": "unavailable", "error": ocr_error or "no OCR result", "warnings": []}
    result = {
        "verdict": verdict,
        "failed_checks": failed,
        "checks": checks,
        "usability": usability,
        "coverage": round(float(coverage), 4) if isinstance(coverage, (int, float)) else None,
        "judge": judge,
        "judge_status": (judge or {}).get("status"),
        "ocr_warnings": warnings,
        "structure_checks": structure_checks,
        "structure_warnings": [] if structure_checks or checks is None else _items(judge, "structure_issues"),
        "target_language": _target_base(target_lang),
        "thresholds": {
            "usability_min": USABILITY_MIN,
            "leftover_ratio_max": LEFTOVER_RATIO_MAX,
            "min_run_digits": MIN_RUN_DIGITS,
            "soft_confidence_max": SOFT_CONFIDENCE_MAX,
            "tolerant_min_digits": TOLERANT_MIN_DIGITS,
            "big_glyph_ratio": BIG_GLYPH_RATIO,
        },
    }
    if source is not None and not source.words:
        result["note"] = "the source OCR found no words: the OCR warnings have nothing to compare"
    return result


# ---------------------------------------------------------------- picking between attempts


def attempt_key(verification: dict | None) -> tuple:
    """Sort key of an attempt, bigger is better.

    A verified attempt beats an unverified one. Among verified attempts: lexicographic on
    (numbers ok, rows ok, garbled ok, partial ok, score ok), then the judge's usability, then its
    coverage (an unknown value counts as below any known one).
    """
    flags = verdict_flags((verification or {}).get("verdict"))
    if flags is None:
        return (0,)
    usability = (verification or {}).get("usability")
    coverage = (verification or {}).get("coverage")
    return (
        1,
        *flags,
        float(usability) if isinstance(usability, (int, float)) else -1.0,
        float(coverage) if isinstance(coverage, (int, float)) else -1.0,
    )


def choose_attempt(verifications: list[dict | None]) -> int:
    """Index of the attempt to show as the result; the earlier attempt wins every tie."""
    best = 0
    for i in range(1, len(verifications)):
        if attempt_key(verifications[i]) > attempt_key(verifications[best]):
            best = i
    return best
