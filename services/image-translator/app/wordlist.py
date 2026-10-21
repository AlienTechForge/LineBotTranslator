"""Compact word list handed to the model, plus a token-budget gate."""

from __future__ import annotations

import math

from .models import ModeError, Word

SOFT_TOKEN_BUDGET = 12_000  # the brief's target; exceeding it only produces a warning
HARD_TOKEN_LIMIT = 150_000  # beyond this we refuse rather than burn money on a pathological scan


def format_word_list(words: list[Word]) -> str:
    """One line per word: ``id x y w h text`` (axis-aligned bbox in ORIGINAL pixels)."""
    lines: list[str] = []
    for w in words:
        text = " ".join(w.text.split())
        if not text:
            continue
        x, y, bw, bh = w.bbox
        lines.append(f"{w.id} {x} {y} {bw} {bh} {text}")
    return "\n".join(lines)


def estimate_tokens(text: str) -> int:
    """Cheap, conservative token estimate (no tokenizer dependency).

    ASCII is counted at ~2.8 chars per token (the list is digit heavy), anything else at
    ~1.1 tokens per character (CJK, Vietnamese diacritics).
    """
    ascii_chars = sum(1 for ch in text if ord(ch) < 128)
    other = len(text) - ascii_chars
    return int(math.ceil(ascii_chars / 2.8 + other * 1.1))


def check_token_budget(
    word_list: str, soft: int = SOFT_TOKEN_BUDGET, hard: int = HARD_TOKEN_LIMIT
) -> tuple[int, str | None]:
    """Return (estimated tokens, warning). Raises ModeError above the hard limit."""
    tokens = estimate_tokens(word_list)
    if tokens > hard:
        raise ModeError(
            f"word list is about {tokens} tokens, above the hard limit of {hard}; "
            "refusing to send (is this a scan with thousands of tiny words?)"
        )
    if tokens > soft:
        return tokens, f"word list is about {tokens} tokens, above the {soft} token target"
    return tokens, None
