"""Word-list formatting, the token gate and the cost table."""

import pytest
from helpers import rect, word

from app.claude_units import PRICES_PER_MTOK, estimate_cost, price_for
from app.models import ModeError
from app.wordlist import check_token_budget, estimate_tokens, format_word_list


def test_word_list_one_line_per_word_with_axis_aligned_bbox():
    words = [
        word(1, "Hello", rect(10, 20, 110, 52)),
        # a rotated polygon: the line carries its axis-aligned bbox
        word(2, "Tilt", [(200, 100), (260, 110), (255, 140), (195, 130)]),
        word(3, "價格", rect(5, 6, 45, 30)),
    ]
    text = format_word_list(words)
    assert text.splitlines() == [
        "w0001 10 20 100 32 Hello",
        "w0002 195 100 65 40 Tilt",
        "w0003 5 6 40 24 價格",
    ]


def test_word_list_skips_blank_words_and_flattens_whitespace():
    words = [word(1, "  ", rect(0, 0, 10, 10)), word(2, "a\nb", rect(0, 0, 10, 10))]
    assert format_word_list(words) == "w0002 0 0 10 10 a b"


def test_token_gate_accepts_small_lists():
    tokens, warning = check_token_budget("w0001 1 2 3 4 hello")
    assert tokens > 0 and warning is None


def test_token_gate_warns_above_the_soft_budget_but_still_returns():
    big = "\n".join(f"w{i:04d} {i} {i} 40 20 word{i}" for i in range(1, 4000))
    tokens, warning = check_token_budget(big)
    assert tokens > 12_000
    assert warning and "12000" in warning


def test_token_gate_refuses_above_the_hard_limit():
    with pytest.raises(ModeError, match="hard limit"):
        check_token_budget("x" * 5000, soft=100, hard=1000)


def test_token_estimate_counts_cjk_heavier_than_ascii():
    assert estimate_tokens("日本語のテキスト") > estimate_tokens("abcdefgh")


@pytest.mark.parametrize(
    "model,expected",
    [
        ("claude-opus-5-5", 4 * 1 + 20 * 1),
        ("claude-sonnet-5-5", 2 + 10),
        ("claude-haiku-5-5", 0.10 + 0.50),
    ],
)
def test_cost_table_per_million_tokens(model, expected):
    assert estimate_cost(model, 1_000_000, 1_000_000) == pytest.approx(expected)


def test_cost_for_realistic_call():
    # 20k input tokens and 3k output tokens on the default model
    assert estimate_cost("claude-opus-5-5", 20_000, 3_000) == pytest.approx(0.08 + 0.06)


def test_cost_unknown_model_is_none_so_the_ui_shows_tokens_only():
    assert estimate_cost("claude-opus-5", 1000, 1000) is None
    assert estimate_cost("some-other-model", 1000, 1000) is None


def test_cost_matches_dated_variants_of_a_known_model():
    assert price_for("claude-opus-5-5-20260101") == PRICES_PER_MTOK["claude-opus-5-5"]
