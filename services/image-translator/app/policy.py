"""The production policy: Mode A first, Mode B only when A does not pass, then the three tiers.

    run(image, target_lang, policy, request_id, deadline) -> Outcome

1. Vision reads the source once (jobs.SourceOcr). No words: status `no_text`.
2. Mode A (pipeline_a.run_mode_a) with the vision judge and no retry of its own: the one retry of the
   lab is replaced by Mode B. The judge does not count `rows` for Mode A (structure_checks=False).
3. A verdict `pass` ends it: status `image`, tier `clean`, reason `a_pass`.
4. Otherwise, when the policy allows it, the OpenRouter budget has room and the deadline leaves at least
   MODE_B_MIN_REMAINING_S, Mode B makes ONE generation (2K, no retry) and the judge looks at it. The
   better of the two (verify.choose_attempt, A first on ties) is the result.
5. The result is a `clean` picture (verdict pass), a `warned` picture (usability at least
   SEND_MIN_USABILITY) or `text_only` (below it, or unverified); with no picture at all the status is
   `error`. A skipped Mode B says why in `reason` (or, when it changes nothing about the status, in the
   attempts and the warnings).

The deadline (`V1_DEADLINE_S` from the start of the request, an absolute `time.monotonic()` value here)
only keeps new work from starting: a call that is already running is not interrupted. Errors inside one
mode never kill the other. Every cost goes to the budget file the moment it is known (Ledger).

Nothing in here logs a text, a translation, an image or a token: the log lines carry statuses and counts.
"""

from __future__ import annotations

import io
import logging
import time
from dataclasses import dataclass, field
from pathlib import Path

from PIL import Image

from . import budget, fonts, jobs, openrouter, pipeline_a, verify
from .config import DEFAULT_OUTPUT_SIZE, get_settings
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
    "policy_a_only",  # Mode B was not allowed by the policy
    "policy_b_only",  # Mode A was not run: the policy is b_only
    "openrouter_budget",  # Mode B was skipped: this month's OpenRouter budget is used up
    "anthropic_budget",  # nothing was started (or Mode B was skipped): this month's Anthropic budget is used up
    "deadline",  # Mode B (or everything) was not started: not enough time left
    "below_threshold",  # text_only: the best picture is below SEND_MIN_USABILITY
    "unverified",  # text_only: the judge could not say anything about the best picture
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


# ---------------------------------------------------------------- pieces


def assemble_translated_text(units: list[ClaudeUnit] | None) -> str | None:
    """The planner's units in the order it gave them, one line each: the translation of a translated unit, the
    source text of a `keep` unit (prices, numbers, names). A unit with an empty translation shows its source
    text. A unit's own line breaks are kept. None when the planner never answered or listed nothing.
    """
    if units is None:
        return None
    lines: list[str] = []
    for unit in units:
        text = (unit.source_text or unit.translation) if unit.keep else (unit.translation or unit.source_text)
        text = text.strip()
        if text:
            lines.append(text)
    return "\n".join(lines) if lines else None


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


def tier_for(verdict: str | None, usability: float | None) -> str | None:
    """`clean` for a verdict of pass, `warned` from SEND_MIN_USABILITY up, else None (the text is sent instead)."""
    if verdict == verify.VERDICT_PASS:
        return "clean"
    if usability is not None and usability >= get_settings().send_min_usability:
        return "warned"
    return None


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
        attempt.png, attempt.error = None, describe_error(exc)
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
        attempt.png, attempt.error = None, describe_error(exc)
    attempt.elapsed_ms = int((time.perf_counter() - t0) * 1000)
    return attempt


# ---------------------------------------------------------------- the policy


def _finish(outcome: Outcome, t_start: float, attempts: list[Attempt], ledger: Ledger, chosen: Attempt | None) -> Outcome:
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

    def fail(reason: str, attempts: list[Attempt], text: dict | None = None) -> Outcome:
        out = Outcome("error", reason, warnings=notes, timings_ms=timings)
        if text:
            out.text = text
        return _finish(out, t_start, attempts, ledger, None)

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
    text = {
        "source_language": verify.detect_source_language(ocr.words)[0] if ocr is not None else None,
        "recognized_text": recognized_text(ocr),
        "translated_text": None,
    }
    if ocr is not None and not any(w.text.strip() for w in ocr.words):
        out = Outcome("no_text", "no_text", warnings=notes, timings_ms=timings, text=text)
        return _finish(out, t_start, [], ledger, None)

    check_opts = {"judge": True, "verify_details": False, "ocr": False}  # no Vision call on the outputs here
    a: Attempt | None = None
    b: Attempt | None = None
    attempts: list[Attempt] = []

    # ---- 2. Mode A
    if policy == "b_only":
        attempts.append(Attempt("A", skipped="policy_b_only"))
    elif ocr is None:
        a = Attempt("A", error="the OCR of the source failed", elapsed_ms=0)
        attempts.append(a)
    else:
        a = _run_mode_a(image, target_lang, work_dir, source, ledger, request_id, check_opts)
        attempts.append(a)
        for line in a.warnings:
            _note(notes, line)
        verification = a.verification or {}
        for item in verification.get("structure_warnings") or []:
            _note(notes, f"A: the judge noted a structure issue that is not counted for Mode A ({item.get('type')}: {item.get('detail')})")
        text["translated_text"] = assemble_translated_text(a.units)

    # ---- 3 and 4. Mode B
    a_passed = a is not None and a.png is not None and a.verdict == verify.VERDICT_PASS
    wants_b = policy in ("both", "b_only") or not a_passed
    b_skip: str | None = None
    if wants_b:
        b_skip = b_skip_reason(policy, deadline - _clock())
        if b_skip is None:
            model = mode_b_model or settings.openrouter_image_model
            b = _run_mode_b(image, target_lang, model, source, ledger, check_opts)
            attempts.append(b)
        else:
            attempts.append(Attempt("B", skipped=b_skip))
            if b_skip != "policy_a_only":
                _note(notes, f"Mode B was skipped: {b_skip}")

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

    chosen = produced[verify.choose_attempt([x.verification for x in produced])]
    tier = tier_for(chosen.verdict, chosen.usability)
    if tier is None:
        reason = "below_threshold" if verify.is_verified(chosen.verdict) else "unverified"
    elif chosen is a and a_passed:
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
    return _finish(out, t_start, attempts, ledger, chosen)
