"""Quoting of OCR / judge fragments inside prompts that go to another model (never as instructions)."""

from __future__ import annotations

MAX_QUOTED_CHARS = 120


def flat(text: object) -> str:
    """A fragment on one line, double quotes turned into single ones, cut at 120 characters."""
    one_line = " ".join(str(text).replace('"', "'").split())
    return one_line[: MAX_QUOTED_CHARS - 1] + "…" if len(one_line) > MAX_QUOTED_CHARS else one_line


def quote(text: object) -> str:
    """`flat` inside double quotes."""
    return '"' + flat(text) + '"'


def unique(items: list[str]) -> list[str]:
    """The non-empty items, each once, in order."""
    return list(dict.fromkeys(i for i in items if i))
