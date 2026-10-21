"""The production policy: Mode A first, Mode B only when A does not pass, then the three tiers.

    run(image, target_lang, policy, request_id, deadline) -> Outcome

1. Vision reads the source once (jobs.SourceOcr). No words: status `no_text`. The median glyph height of
   the words with text goes to the response (`source.median_glyph_px`).
2. Mode A (pipeline_a.run_mode_a) with the vision judge and no retry of its own: the one retry of the
   lab is replaced by Mode B. The judge does not count `rows` for Mode A (structure_checks=False).
   Exception: with `a_then_b`, a median glyph height below MODE_A_MIN_GLYPH_PX (and above 0) means the
   text is too small to erase and set again: Mode B goes first (step 4) and, when its picture can be
   sent, it is the result (reason `small_text`) and Mode A never runs. When B sent nothing and more than
   MODE_A_MIN_REMAINING_S is left, Mode A runs after it, so the planner's text fallback still exists.
3. A verdict `pass` ends it: status `image`, tier `clean`, reason `a_pass`.
4. Otherwise, when the policy allows it, the OpenRouter budget has room and the deadline leaves at least
   MODE_B_MIN_REMAINING_S, Mode B makes ONE generation (2K, no retry) and the judge looks at it.
5. Of the pictures that were made, the one that can be SENT best is the result: `clean` (verdict pass)
   before `warned` (usability at least SEND_MIN_USABILITY) before a picture that cannot be sent; inside
   one of these groups verify.attempt_key decides (verified before unverified, the five checks in
   order, usability, coverage), and A wins every exact tie. So a Mode A picture that is below the
   line never beats a Mode B picture above it, whatever their check flags say.
   The result is the `clean` or `warned` picture, or `text_only` when the best one is below the line
   or unverified; with no picture at all, or when the text fallback does not exist because Mode A never
   ran, the status is `error`. A skipped Mode B says why in `reason` (or, when it changes nothing about
   the status, in the attempts and the warnings).

The deadline (`V1_DEADLINE_S` from the start of the request, an absolute `time.monotonic()` value here)
only keeps new work from starting: a call that is already running is not interrupted. Errors inside one
mode never kill the other. Every cost goes to the budget file the moment it is known (Ledger).

Nothing in here logs a text, a translation, an image or a token: the log lines carry statuses and counts.
"""

from __future__ import annotations

import io
import logging
import statistics
import time
from dataclasses import dataclass, field
from pathlib import Path

from PIL import Image

from . import budget, fonts, jobs, openrouter, pipeline_a, verify
from .config import DEFAULT_OUTPUT_SIZE, MODE_A_MIN_REMAINING_S, get_settings
from .imaging import LoadedImage
from .models import ClaudeUnit
from .redaction import describe_error

log = logging.getLogger("lab.policy")

POLICIES = ("a_then_b", "a_only", "b_only", "both")
STATUSES = ("image", "text_only", "no_text", "error")
TIERS = ("clean", "warned")

# every `reason` of a response (README.md lists them; a test keeps the two in sync)
REASONS = (
    "a_pass",  # image, clean: Mode A passed
    "b_better",  # image: Mode B beat a Mode A picture
    "a_better",  # image: Mode A beat a Mode B picture
    "a_failed",  # image: Mode A made no picture, Mode B's is the result (or the error when nothing else happened)
    "b_failed",  # image: Mode A is the result, Mode B failed (or the error when B alone was asked for)
    "small_text",  # image: the source glyphs are too small for Mode A (MODE_A_MIN_GLYPH_PX), Mode B's picture is the result
    "policy_a_only",  # Mode B was not allowed by the policy
    "policy_b_only",  # Mode A was not run: the policy is b_only
    "openrouter_budget",  # Mode B was skipped: this month's OpenRouter budget is used up
    "anthropic_budget",  # nothing was started (or Mode B was skipped): this month's Anthropic budget is used up
    "deadline",  # Mode B (or everything) was not started: not enough time left
    "below_threshold",  # text_only: the best picture is below SEND_MIN_USABILITY (error: same, but Mode A never ran)
    "unverified",  # text_only: the judge could not say anything about the best picture (error: same, Mode A never ran)
    "no_text",  # no_text: Vision found no words
    "both_failed",  # error: Mode A and Mode B both failed
    "internal_error",  # error: the policy itself crashed on something unforeseen (never a 500)
    "service_token_missing",  # 503 of /v1: SERVICE_TOKEN is not set
)

MAX_WARNINGS = 30
MAX_WARNING_CHARS = 400

_clock = time.monotonic  # tests replace this to move the clock


# ---------------------------------------------------------------- the money of one request


class Ledger:
    """What one request cost; every amount goes to the budget file as soon as it is known.

    In mock mode everything counts as 0 (the canned answers come with price-table estimates that were
    never paid). An unknown price (a model that is not in the price table, or an OpenRouter answer without
    `usage.cost`) counts as 0 and says so once in the warnings.
    """

    def __init__(self, warnings: list[str]) -> None:
        self.anthropic_usd = 0.0
        self.openrouter_usd = 0.0
        self._warnings = warnings
        self._mock = get_settings().mock
        self._said: set[str] = set()

    def _amount(self, usd, what: str, billed: bool) -> float:
        if self._mock:
            return 0.0
        if isinstance(usd, bool) or not isinstance(usd, (int, float)) or usd < 0:
            if billed and what not in self._said:
                self._said.add(what)
                self._warnings.append(f"the price of the {what} is unknown: counted as 0 in the budget")
            return 0.0
        return float(usd)

    def anthropic(self, usd, what: str, billed: bool = True) -> float:
        amount = self._amount(usd, what, billed)
        self.anthropic_usd += amount
        budget.add(anthropic_usd=amount)
        return amount

    def openrouter(self, usd, what: str, billed: bool = True) -> float:
        amount = self._amount(usd, what, billed)
        self.openrouter_usd += amount
        budget.add(openrouter_usd=amount)
        return amount

    def costs(self) -> dict:
        return {
            "anthropic_usd": round(self.anthropic_usd, 6),
            "openrouter_usd": round(self.openrouter_usd, 6),
            "total_usd": round(self.anthropic_usd + self.openrouter_usd, 6),
        }


# ---------------------------------------------------------------- one attempt


@dataclass
class Attempt:
    mode: str  # "A" | "B"
    png: bytes | None = None
    size: tuple[int, int] | None = None
    verification: dict | None = None
    error: str | None = None
    error_type: str | None = None  # the exception class only (the message may quote the image): for the log line
    skipped: str | None = None  # why this mode did not run at all
    elapsed_ms: int | None = None
    judge_ms: int | None = None
    cost_anthropic: float = 0.0
    cost_openrouter: float = 0.0
    units: list[ClaudeUnit] | None = None  # Mode A: what the planner answered
    warnings: list[str] = field(default_factory=list)

    @property
    def verdict(self) -> str | None:
        """The judge's verdict of the picture; `unverified` when there is a picture the check could not report on."""
        if self.png is None:
            return None
        return (self.verification or {}).get("verdict") or verify.VERDICT_UNVERIFIED

    @property
    def usability(self) -> float | None:
        value = (self.verification or {}).get("usability")
        return float(value) if isinstance(value, (int, float)) and not isinstance(value, bool) else None

    @property
    def coverage(self) -> float | None:
        value = (self.verification or {}).get("coverage")
        return float(value) if isinstance(value, (int, float)) and not isinstance(value, bool) else None

    def to_dict(self, selected: bool) -> dict:
        return {
            "mode": self.mode,
            "verdict": self.verdict,
            "usability": self.usability if self.png else None,
            "cost_usd": round(self.cost_anthropic + self.cost_openrouter, 6),
            "elapsed_ms": self.elapsed_ms,
            "error": self.error,
            "skipped": self.skipped,
            "selected": selected,
        }


@dataclass
class Outcome:
    """What the policy decided; v1.py wraps it into the response (request id, image path, budget)."""

    status: str
    reason: str | None
    tier: str | None = None
    mode: str | None = None
    verdict: str | None = None
    usability: float | None = None
    coverage: float | None = None
    png: bytes | None = None  # the picture to send (status `image` only)
    size: tuple[int, int] | None = None
    text: dict = field(default_factory=lambda: {"source_language": None, "recognized_text": "", "translated_text": None})
    judge: dict | None = None
    costs: dict = field(default_factory=lambda: {"anthropic_usd": 0.0, "openrouter_usd": 0.0, "total_usd": 0.0})
    timings_ms: dict = field(default_factory=dict)
    attempts: list[dict] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    source: dict | None = None  # {width, height, words, median_glyph_px} of the Vision read; None when Vision gave nothing


# ---------------------------------------------------------------- pieces

_FRAGMENT_MAX_BRACKET_CHARS = 3  # a text this short with a lonely bracket is a piece of a bigger text
_BRACKET_PAIRS = (("(（", ")）"), ("[", "]"), ("「", "」"), ("『", "』"), ("【", "】"))  # (openers, closers)


def _is_cjk(ch: str) -> bool:
    """Han, kana, Hangul or Bopomofo: a script in which one character can be a word."""
    code = ord(ch)
    return (
        0x4E00 <= code <= 0x9FFF  # unified ideographs
        or 0x3400 <= code <= 0x4DBF  # extension A
        or 0xF900 <= code <= 0xFAFF  # compatibility ideographs
        or 0x20000 <= code <= 0x2FA1F  # extensions B and later, compatibility supplement
        or 0x3040 <= code <= 0x30FF  # hiragana, katakana
        or 0x31F0 <= code <= 0x31FF  # katakana extensions
        or 0x3100 <= code <= 0x312F  # bopomofo
        or 0xAC00 <= code <= 0xD7AF  # Hangul syllables
    )


def _is_fragment(text: str) -> bool:
    """True for a line of the text fallback that is a piece of OCR rather than something to read.

    A line is a fragment when it has no letter, digit or CJK character at all (punctuation and symbols only);
    or it is one character that is not CJK (`2`, `C`, `-`; a single Chinese / Japanese / Korean character is a
    word and stays); or it is at most 3 characters long and has a closing bracket (`)` `]` `」` `』` `）` `】`)
    with no opening one, or an opening bracket with no closing one (`さい)`). A short price (`$12`) or a date
    is none of these.
    """
    text = text.strip()
    if not any(ch.isalnum() or _is_cjk(ch) for ch in text):
        return True
    if len(text) == 1 and not _is_cjk(text):
        return True
    if len(text) <= _FRAGMENT_MAX_BRACKET_CHARS:
        for openers, closers in _BRACKET_PAIRS:
            if sum(text.count(c) for c in openers) != sum(text.count(c) for c in closers):
                return True
    return False


def assemble_translated_text(units: list[ClaudeUnit] | None) -> str | None:
    """The planner's units in the order it gave them, one line each: the translation of a translated unit, the
    source text of a `keep` unit (prices, numbers, names). A unit with an empty translation shows its source
    text. A unit's own line breaks are kept. None when the planner never answered or listed nothing.

    This is what the user reads when no picture can be sent, so OCR fragments are left out (`_is_fragment`: a
    lone `2`, a `さい)`, a line of symbols) and a line that repeats the one before it is said once. When every line
    would go, the unfiltered text is returned: the filter never turns an answer into nothing.
    """
    if units is None:
        return None
    lines: list[str] = []
    for unit in units:
        text = (unit.source_text or unit.translation) if unit.keep else (unit.translation or unit.source_text)
        text = text.strip()
        if text:
            lines.append(text)
    if not lines:
        return None
    kept: list[str] = []
    for line in lines:
        if _is_fragment(line) or (kept and kept[-1] == line):
            continue
        kept.append(line)
    return "\n".join(kept or lines)


def median_glyph_px(ocr) -> float:
    """The median `box_height` of the words that have text, in source pixels, to one decimal; 0.0 when no word
    has a box (a word without a box does not count, so a Vision result without boxes never reads as tiny)."""
    if ocr is None:
        return 0.0
    heights = [h for h in (w.box_height for w in ocr.words if w.text.strip()) if h > 0]
    return round(float(statistics.median(heights)), 1) if heights else 0.0


def source_block(ocr) -> dict | None:
    """The `source` block of the response; None when Vision gave no result."""
    if ocr is None:
        return None
    return {
        "width": int(ocr.width),
        "height": int(ocr.height),
        "words": sum(1 for w in ocr.words if w.text.strip()),
        "median_glyph_px": median_glyph_px(ocr),
    }


def text_too_small(policy: str, median_px: float) -> bool:
    """True when Mode A should not go first: `a_then_b`, and a median glyph height that is known (above 0) and
    below MODE_A_MIN_GLYPH_PX. Never in mock mode (the canned words are scaled to whatever was uploaded, so
    their height says nothing about real glyphs) and never when MODE_A_MIN_GLYPH_PX is 0."""
    settings = get_settings()
    limit = settings.mode_a_min_glyph_px
    return policy == "a_then_b" and not settings.mock and limit > 0 and 0 < median_px < limit


def recognized_text(ocr) -> str:
    """Vision's full text of the source (its own line breaks), else the words grouped by paragraph."""
    if ocr is None:
        return ""
    text = (ocr.text or "").strip()
    if text:
        return text
    from .judge import ocr_lines

    return "\n".join(ocr_lines(ocr))


def judge_block(verification: dict | None) -> dict | None:
    """The judge's findings that matter to a caller; None when the judge gave no answer."""
    judge = (verification or {}).get("judge") or {}
    if judge.get("status") != "ok":
        return None
    return {
        "summary": str(judge.get("summary") or ""),
        "altered_numbers": list(judge.get("altered_numbers") or []),
        "untranslated_major": [i for i in judge.get("untranslated") or [] if i.get("major")],
        "legibility_major": [i for i in judge.get("legibility_issues") or [] if i.get("major")],
    }


def b_skip_reason(policy: str, remaining: float) -> str | None:
    """Why Mode B may not start now (None: it may). The order is the policy, the OpenRouter budget, the clock,
    then the Anthropic budget (the judge of Mode B needs it)."""
    if policy == "a_only":
        return "policy_a_only"
    if not budget.allows_openrouter():
        return "openrouter_budget"
    if remaining < get_settings().mode_b_min_remaining_s:
        return "deadline"
    if not budget.allows_anthropic():
        return "anthropic_budget"
    return None


def a_skip_reason(remaining: float) -> str | None:
    """Why Mode A may not start after a Mode B that sent nothing (None: it may): MODE_A_MIN_REMAINING_S or less
    left, or no Anthropic budget (the planner and the judge need it)."""
    if remaining <= MODE_A_MIN_REMAINING_S:
        return "deadline"
    if not budget.allows_anthropic():
        return "anthropic_budget"
    return None


def tier_for(verdict: str | None, usability: float | None) -> str | None:
    """`clean` for a verdict of pass, `warned` from SEND_MIN_USABILITY up, else None (the text is sent instead)."""
    if verdict == verify.VERDICT_PASS:
        return "clean"
    if usability is not None and usability >= get_settings().send_min_usability:
        return "warned"
    return None


_TIER_RANK = {None: 0, "warned": 1, "clean": 2}


def attempt_rank(attempt: Attempt) -> tuple:
    """Sort key of a produced picture, bigger is better: what can be sent first (`clean`, then `warned`, then
    nothing), and inside the same group verify.attempt_key (verified before unverified; numbers, rows, garbled,
    partial, score; usability; coverage)."""
    return (_TIER_RANK[tier_for(attempt.verdict, attempt.usability)], verify.attempt_key(attempt.verification))


def choose(produced: list[Attempt]) -> Attempt:
    """The picture to use; the earlier one of the list wins every exact tie."""
    best = produced[0]
    for candidate in produced[1:]:
        if attempt_rank(candidate) > attempt_rank(best):
            best = candidate
    return best


def _fmt(value, spec: str = ".2f") -> str:
    return "-" if value is None else format(value, spec)


def _log_attempt(request_id: str, attempt: Attempt) -> None:
    """One line per finished attempt: statuses and numbers only (an error is its class name, never its message)."""
    cost = attempt.cost_anthropic + attempt.cost_openrouter
    if attempt.skipped:
        log.info("stage=policy request=%s mode=%s skipped=%s", request_id, attempt.mode, attempt.skipped)
    elif attempt.png is None:
        log.info(
            "stage=policy request=%s mode=%s error=%s ms=%s cost=%.4f",
            request_id, attempt.mode, attempt.error_type or "Error", attempt.elapsed_ms, cost,
        )  # fmt: skip
    else:
        log.info(
            "stage=policy request=%s mode=%s verdict=%s usability=%s coverage=%s ms=%s cost=%.4f",
            request_id, attempt.mode, attempt.verdict, _fmt(attempt.usability), _fmt(attempt.coverage),
            attempt.elapsed_ms, cost,
        )  # fmt: skip


def _png_size(png: bytes) -> tuple[int, int]:
    with Image.open(io.BytesIO(png)) as im:
        return im.size


def _note(warnings: list[str], text: str) -> None:
    text = " ".join(text.split())[:MAX_WARNING_CHARS]
    if text and text not in warnings and len(warnings) < MAX_WARNINGS:
        warnings.append(text)


# ---------------------------------------------------------------- the two modes


def _run_mode_a(
    image: LoadedImage, lang: str, work_dir: Path, source, ledger: Ledger, request_id: str, check_opts: dict
) -> Attempt:
    attempt = Attempt("A")
    t0 = time.perf_counter()

    def on_plan(call) -> None:  # the planner has answered: its text and its cost are ours whatever happens next
        attempt.units = list(call.units)
        attempt.cost_anthropic += ledger.anthropic(call.cost_usd, "planner")

    def check(png: bytes, size: tuple[int, int]) -> dict:
        fields = jobs.check_output(source, image, png, size, lang, check_opts, structure_checks=False)
        attempt.cost_anthropic += ledger.anthropic(
            fields.get("judge_cost_usd"), "judge", billed=fields.get("judge_status") == "ok"
        )
        attempt.judge_ms = fields.get("judge_ms")
        return fields

    try:
        result = pipeline_a.run_mode_a(
            image, lang, work_dir, source, check=check, retry=False,
            meta={"request_id": request_id, "target_lang": lang}, on_plan=on_plan,
        )  # fmt: skip
        attempt.png = (work_dir / result["output"]).read_bytes()
        attempt.size = _png_size(attempt.png)
        attempt.verification = result["verification"]
        for line in result.get("warnings") or []:
            attempt.warnings.append(f"A: {line}")
    except Exception as exc:  # noqa: BLE001 - Mode B may still do the job
        log.warning("stage=policy mode A failed: %s", type(exc).__name__)
        attempt.png, attempt.error, attempt.error_type = None, describe_error(exc), type(exc).__name__
    attempt.elapsed_ms = int((time.perf_counter() - t0) * 1000)
    return attempt


def _run_mode_b(
    image: LoadedImage, lang: str, model: str, source, ledger: Ledger, check_opts: dict
) -> Attempt:
    attempt = Attempt("B")
    t0 = time.perf_counter()
    try:
        with jobs.GENERATION_SLOTS:  # at most MAX_CONCURRENT_GENERATIONS generations in flight, whoever asked
            generated = openrouter.run_image_model(image, lang, model, resolution=DEFAULT_OUTPUT_SIZE)
        attempt.cost_openrouter += ledger.openrouter((generated.get("usage") or {}).get("cost"), "image generation")
        png = generated["png"]
        size = tuple(generated["size"])
        fields = jobs.check_output(source, image, png, size, lang, check_opts, structure_checks=True)
        attempt.cost_anthropic += ledger.anthropic(
            fields.get("judge_cost_usd"), "judge", billed=fields.get("judge_status") == "ok"
        )
        attempt.judge_ms = fields.get("judge_ms")
        attempt.png, attempt.size = png, (int(size[0]), int(size[1]))
        attempt.verification = fields.get("verification")
    except Exception as exc:  # noqa: BLE001 - Mode A's picture (if any) is still the answer
        log.warning("stage=policy mode B failed: %s", type(exc).__name__)
        attempt.png, attempt.error, attempt.error_type = None, describe_error(exc), type(exc).__name__
    attempt.elapsed_ms = int((time.perf_counter() - t0) * 1000)
    return attempt


# ---------------------------------------------------------------- the policy


def _finish(
    outcome: Outcome, t_start: float, attempts: list[Attempt], ledger: Ledger, chosen: Attempt | None, source: dict | None = None
) -> Outcome:
    outcome.source = source
    outcome.attempts = [a.to_dict(a is chosen) for a in attempts]
    outcome.costs = ledger.costs()
    by_mode = {a.mode: a for a in attempts}
    judge_ms = [a.judge_ms for a in attempts if a.judge_ms is not None]
    outcome.timings_ms.update(
        mode_a=by_mode["A"].elapsed_ms if "A" in by_mode else None,
        mode_b=by_mode["B"].elapsed_ms if "B" in by_mode else None,
        judge=sum(judge_ms) if judge_ms else None,
        total=int((_clock() - t_start) * 1000),
    )
    return outcome


def run(
    image: LoadedImage,
    target_lang: str,
    policy: str,
    request_id: str,
    deadline: float,
    *,
    work_dir: Path,
    mode_b_model: str | None = None,
    warnings: list[str] | None = None,
) -> Outcome:
    """Translate one image. `deadline` is an absolute `time.monotonic()` value; `target_lang` is already normalised
    (config.normalize_lang); `work_dir` receives the scratch files of Mode A (the caller deletes it).
    """
    t_start = _clock()
    settings = get_settings()
    if policy not in POLICIES:
        raise ValueError(f"unknown policy {policy!r}")
    notes: list[str] = list(warnings or [])
    for line in fonts.font_notes(target_lang):
        _note(notes, line)
    ledger = Ledger(notes)
    budget.add(requests=1)
    timings: dict = {"ocr": None}
    source_info: dict | None = None

    def fail(reason: str, attempts: list[Attempt], text: dict | None = None, picture: Attempt | None = None) -> Outcome:
        out = Outcome("error", reason, warnings=notes, timings_ms=timings)
        if text:
            out.text = text
        if picture is not None:  # a picture that was made but cannot be sent: its verdict explains the error
            out.verdict, out.usability, out.coverage = picture.verdict, picture.usability, picture.coverage
            out.judge = judge_block(picture.verification)
        return _finish(out, t_start, attempts, ledger, None, source_info)

    if not budget.allows_anthropic():  # every path needs Claude (the planner or the judge): call nothing
        return fail("anthropic_budget", [])
    if deadline - _clock() <= 0:
        return fail("deadline", [])

    # ---- 1. Vision, once
    source = jobs.SourceOcr(image)
    ocr = None
    try:
        ocr = source.get()
    except Exception as exc:  # noqa: BLE001 - Mode A cannot run, Mode B and its judge still can
        log.warning("stage=policy source OCR failed: %s", type(exc).__name__)
        _note(notes, f"the OCR of the source failed ({describe_error(exc)}): Mode A is not possible")
    timings["ocr"] = source.ms
    source_info = source_block(ocr)
    glyph_px = source_info["median_glyph_px"] if source_info else 0.0
    text = {
        "source_language": verify.detect_source_language(ocr.words)[0] if ocr is not None else None,
        "recognized_text": recognized_text(ocr),
        "translated_text": None,
    }
    if ocr is not None and not any(w.text.strip() for w in ocr.words):
        out = Outcome("no_text", "no_text", warnings=notes, timings_ms=timings, text=text)
        return _finish(out, t_start, [], ledger, None, source_info)

    check_opts = {"judge": True, "verify_details": False, "ocr": False}  # no Vision call on the outputs here
    a: Attempt | None = None
    b: Attempt | None = None
    b_skip: str | None = None
    attempts: list[Attempt] = []

    def record(attempt: Attempt) -> Attempt:
        attempts.append(attempt)
        _log_attempt(request_id, attempt)
        return attempt

    def run_a() -> None:
        nonlocal a
        a = record(_run_mode_a(image, target_lang, work_dir, source, ledger, request_id, check_opts))
        for line in a.warnings:
            _note(notes, line)
        verification = a.verification or {}
        for item in verification.get("structure_warnings") or []:
            _note(notes, f"A: the judge noted a structure issue that is not counted for Mode A ({item.get('type')}: {item.get('detail')})")
        text["translated_text"] = assemble_translated_text(a.units)

    def run_b() -> None:
        nonlocal b, b_skip
        b_skip = b_skip_reason(policy, deadline - _clock())
        if b_skip is None:
            model = mode_b_model or settings.openrouter_image_model
            b = record(_run_mode_b(image, target_lang, model, source, ledger, check_opts))
        else:
            record(Attempt("B", skipped=b_skip))
            if b_skip != "policy_a_only":
                _note(notes, f"Mode B was skipped: {b_skip}")

    def passed(attempt: Attempt | None) -> bool:
        return attempt is not None and attempt.png is not None and attempt.verdict == verify.VERDICT_PASS

    # ---- 2, 3 and 4. The modes: Mode A first, then Mode B unless A passed; or, for tiny text, B first
    small_text = ocr is not None and text_too_small(policy, glyph_px)
    if small_text:
        small_note = f"the source text is too small (median glyph {glyph_px:g} px < MODE_A_MIN_GLYPH_PX {settings.mode_a_min_glyph_px:g})"
        run_b()
        if b is not None and b.png is not None and tier_for(b.verdict, b.usability) is not None:
            record(Attempt("A", skipped="small_text"))  # B's picture can be sent: Mode A is never run
            _note(notes, f"Mode A skipped: {small_note}")
        else:
            a_skip = a_skip_reason(deadline - _clock())
            if a_skip is None:  # B sent nothing: Mode A still gives the text fallback and a second chance
                _note(notes, f"Mode A ran only after Mode B: {small_note}")
                run_a()
            else:
                record(Attempt("A", skipped=a_skip))
                _note(notes, f"Mode A skipped: {small_note}")
                _note(notes, f"Mode A was not started after Mode B: {a_skip}")
    else:
        if policy == "b_only":
            record(Attempt("A", skipped="policy_b_only"))
        elif ocr is None:
            a = record(Attempt("A", error="the OCR of the source failed", error_type="SourceOcrFailed", elapsed_ms=0))
        else:
            run_a()
        if policy in ("both", "b_only") or not passed(a):
            run_b()

    # ---- 5. the result
    produced = [x for x in (a, b) if x is not None and x.png is not None]
    if not produced:
        failed = [x for x in (a, b) if x is not None and x.error]
        if a is not None and a.error and b is not None and b.error:
            reason = "both_failed"
        elif b_skip in ("openrouter_budget", "anthropic_budget", "deadline"):
            reason = b_skip
        elif b is not None and b.error:
            reason = "b_failed"
        elif a is not None and a.error:
            reason = "a_failed"
        else:
            reason = "both_failed"
        for x in failed:
            _note(notes, f"Mode {x.mode} failed: {x.error}")
        return fail(reason, attempts, text)

    chosen = choose(produced)
    tier = tier_for(chosen.verdict, chosen.usability)
    never_ran_a = small_text and a is None  # no planner answer: there is no text to send instead of a picture
    if tier is None:
        reason = "below_threshold" if verify.is_verified(chosen.verdict) else "unverified"
        if never_ran_a:
            return fail(reason, attempts, text, picture=chosen)
    elif never_ran_a:
        reason = "small_text"
    elif chosen is a and passed(a):
        reason = "a_pass"
    elif chosen is a:
        reason = "a_better" if b is not None and b.png is not None else ("b_failed" if b is not None else (b_skip or "a_better"))
    else:
        reason = "b_better" if a is not None and a.png is not None else ("a_failed" if a is not None else "policy_b_only")
    if a is not None and a.error and b is not None and b.png is not None:
        _note(notes, f"Mode A failed: {a.error}")
    if b is not None and b.error:
        _note(notes, f"Mode B failed: {b.error}")

    out = Outcome(
        "text_only" if tier is None else "image",
        reason,
        tier=tier,
        mode=chosen.mode,
        verdict=chosen.verdict,
        usability=chosen.usability,
        coverage=chosen.coverage,
        png=chosen.png if tier is not None else None,
        size=chosen.size if tier is not None else None,
        text=text,
        judge=judge_block(chosen.verification),
        warnings=notes,
        timings_ms=timings,
    )
    return _finish(out, t_start, attempts, ledger, chosen, source_info)
