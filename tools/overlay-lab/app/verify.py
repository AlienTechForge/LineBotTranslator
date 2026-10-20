"""Mode B verification: did the image model keep every digit, translate every line, and write real text?

Round 2 showed two defects of whole-image rewrites that a quick look misses: the model re-drew
handwritten digits (a phone number 77446716 came back as 09446916) and it left one title line
untranslated. Both are found by running Vision OCR on the SOURCE and on the OUTPUT and comparing
the two word lists:

* digit integrity - every run of 2+ digits of the source must still be in the output;
* leftover source language - the share of source-language words that survive in the output
  must stay at or below LEFTOVER_RATIO_MAX, and no leftover word may be much bigger than the
  rest of the output (a leftover title is far worse than a leftover footnote).

Round 3 showed what those two cannot see, so round 4 added:

* the semantic judge (judge.py): Claude reads the OCR text of both sides and reports garbled
  text (invented words, random characters in the target script) and a coverage score;
* a handwriting-tolerant digit check: Vision misreads handwritten digits (77446716 read as
  97446716), so a source run that comes only from low-confidence words is *soft*: it never fails
  the verdict, and for 5+ digits an output run one digit away counts as a match.

Thresholds (the same ones the README lists):

* digit run: 2 or more digits, a single `- : / . ,` allowed between two digits, separators
  stripped (``12:30`` -> ``1230``). The output side is every digit of the output OCR text,
  concatenated; each distinct HARD source run (confidence >= 0.75, or unknown) must be a
  substring of it (a run that is printed several times in the source is checked once).
* soft run (every word it comes from has confidence < 0.75): matched when it is a substring, or
  (5+ digits) when an output run of the same length differs in at most one digit; otherwise it is
  reported in `unverified_digit_runs` and never fails.
* leftover: ratio <= 0.08, and no leftover word taller than 2x the median word height.
* garbled: the judge lists garbled segments, or its coverage is below 0.90.

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
BIG_GLYPH_RATIO = 2.0  # a leftover word taller than this x the median word height forces `partial`
COVERAGE_MIN = 0.90  # judge coverage below this is `garbled`

VERDICT_PASS = "pass"
VERDICT_PARTIAL = "partial"
VERDICT_GARBLED = "garbled"
VERDICT_GARBLED_PARTIAL = "garbled+partial"
VERDICT_DIGITS = "digits-changed"
VERDICT_DIGITS_PARTIAL = "digits-changed+partial"
VERDICT_DIGITS_GARBLED = "digits-changed+garbled"
VERDICT_ALL = "digits-changed+garbled+partial"
VERDICT_UNVERIFIED = "unverified"  # the check itself could not run (no Vision credentials ...)

# Best to worst. The order is lexicographic on (digits kept, no garbled text, no leftover): an
# attempt that changed digits never beats one that did not, and among attempts with the same digit
# status one with garbled text never beats one without. "unverified" is below all of them.
VERDICTS_BEST_TO_WORST = (
    VERDICT_PASS,
    VERDICT_PARTIAL,
    VERDICT_GARBLED,
    VERDICT_GARBLED_PARTIAL,
    VERDICT_DIGITS,
    VERDICT_DIGITS_PARTIAL,
    VERDICT_DIGITS_GARBLED,
    VERDICT_ALL,
)
VERDICT_RANK = {v: len(VERDICTS_BEST_TO_WORST) - 1 - i for i, v in enumerate(VERDICTS_BEST_TO_WORST)}

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


def check_garbled(judge: dict | None) -> dict:
    """What the judge record says about garbled text; a judge that did not answer decides nothing."""
    if not judge or judge.get("status") != "ok":
        return {
            "garbled_ok": True,
            "coverage": None,
            "garbled_count": 0,
            "judge_status": (judge or {}).get("status"),
        }
    garbled = judge.get("garbled_segments") or []
    coverage = float(judge.get("coverage") if judge.get("coverage") is not None else 1.0)
    return {
        "garbled_ok": not garbled and coverage >= COVERAGE_MIN,
        "coverage": round(coverage, 4),
        "garbled_count": len(garbled),
        "judge_status": "ok",
    }


def verdict_for(digits_ok: bool, leftover_ok: bool, garbled_ok: bool = True) -> str:
    """pass, or the problems joined with `+` in the order digits-changed, garbled, partial."""
    problems = []
    if not digits_ok:
        problems.append("digits-changed")
    if not garbled_ok:
        problems.append("garbled")
    if not leftover_ok:
        problems.append("partial")
    return "+".join(problems) if problems else VERDICT_PASS


def verify_output(
    source: OcrResult,
    output: OcrResult,
    target_lang: str,
    include_text: bool = False,
    judge: dict | None = None,
) -> dict:
    """The checks, the verdict, and everything the page and the retry prompt need.

    `judge` is the record of judge.judge_output for this output (None when the judge is off).
    """
    output_text = output.text or " ".join(w.text for w in output.words)
    digits = check_digits(source.words, output_text)
    source_lang, method = detect_source_language(source.words)
    leftover = check_leftover(source.words, output.words, target_lang, source_lang)
    garbled = check_garbled(judge)
    result = {
        "verdict": verdict_for(digits["digits_ok"], leftover["leftover_ok"], garbled["garbled_ok"]),
        **digits,
        **leftover,
        **garbled,
        "judge": judge,
        "source_language": source_lang,
        "source_language_method": method,
        "target_language": _target_base(target_lang),
        "source_word_count": len(source.words),
        "output_word_count": len(output.words),
        "thresholds": {
            "leftover_ratio_max": LEFTOVER_RATIO_MAX,
            "min_run_digits": MIN_RUN_DIGITS,
            "soft_confidence_max": SOFT_CONFIDENCE_MAX,
            "tolerant_min_digits": TOLERANT_MIN_DIGITS,
            "big_glyph_ratio": BIG_GLYPH_RATIO,
            "coverage_min": COVERAGE_MIN,
        },
    }
    if not source.words:
        result["note"] = "the source OCR found no words, nothing to compare"
    if include_text:
        result["output_text"] = output_text
    return result


# ---------------------------------------------------------------- picking between attempts


def attempt_key(verification: dict | None) -> tuple:
    """Sort key of an attempt, bigger is better.

    First the verdict (VERDICTS_BEST_TO_WORST; an attempt that changed digits never beats one
    that did not, and with equal digits garbled text never beats clean text), then the judge's
    coverage (higher; unknown counts as below any known value), fewer missing digit runs and a
    lower leftover ratio. Attempts that could not be verified rank below every verdict.
    """
    if not verification or verification.get("verdict") not in VERDICT_RANK:
        return (-1, -1.0, 0, 0.0)
    coverage = verification.get("coverage")
    return (
        VERDICT_RANK[verification["verdict"]],
        float(coverage) if isinstance(coverage, (int, float)) else -1.0,
        -len(verification.get("missing_digit_runs") or []),
        -float(verification.get("leftover_ratio") or 0.0),
    )


def choose_attempt(verifications: list[dict | None]) -> int:
    """Index of the attempt to show as the result; the earlier attempt wins every tie."""
    best = 0
    for i in range(1, len(verifications)):
        if attempt_key(verifications[i]) > attempt_key(verifications[best]):
            best = i
    return best
