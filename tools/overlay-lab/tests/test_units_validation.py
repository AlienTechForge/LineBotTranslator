"""Schema validation and the unknown / duplicate word-id rules."""

import json

import pytest

from app.claude_units import UNITS_SCHEMA, parse_units, validate_units
from app.models import ClaudeUnit, ClaudeUnits, ModeError

VALID = {"w0001", "w0002", "w0003", "w0004"}


def unit(ids, **kw):
    base = dict(source_text="s", translation="t", direction="horizontal", rotated=False, role="body", keep=False, notes="")
    base.update(kw)
    return ClaudeUnit(word_ids=ids, **base)


def test_valid_units_pass_through_untouched():
    units = ClaudeUnits(units=[unit(["w0001", "w0002"]), unit(["w0003"])])
    accepted, notes = validate_units(units, VALID)
    assert [u.word_ids for u in accepted] == [["w0001", "w0002"], ["w0003"]]
    assert notes == []


def test_unit_with_an_unknown_id_is_rejected_as_a_whole():
    units = ClaudeUnits(units=[unit(["w0001", "w9999"]), unit(["w0002"])])
    accepted, notes = validate_units(units, VALID)
    assert [u.word_ids for u in accepted] == [["w0002"]]
    assert any("unknown" in n and "w9999" in n for n in notes)


def test_duplicate_id_in_a_later_unit_is_dropped_and_the_rest_survives():
    units = ClaudeUnits(units=[unit(["w0001", "w0002"]), unit(["w0002", "w0003"])])
    accepted, notes = validate_units(units, VALID)
    assert [u.word_ids for u in accepted] == [["w0001", "w0002"], ["w0003"]]
    assert any("duplicate" in n and "w0002" in n for n in notes)


def test_duplicate_inside_one_unit_keeps_the_first_occurrence_only():
    accepted, _ = validate_units(ClaudeUnits(units=[unit(["w0001", "w0001", "w0002"])]), VALID)
    assert accepted[0].word_ids == ["w0001", "w0002"]


def test_unit_that_only_repeats_used_ids_disappears():
    units = ClaudeUnits(units=[unit(["w0001"]), unit(["w0001"])])
    accepted, notes = validate_units(units, VALID)
    assert len(accepted) == 1
    assert any("no word ids left" in n for n in notes)


def test_parse_rejects_non_json_and_wrong_shapes():
    with pytest.raises(ModeError, match="not valid JSON"):
        parse_units("sorry, here is your JSON: {")
    with pytest.raises(ModeError, match="schema"):
        parse_units(json.dumps({"units": [{"word_ids": "w0001"}]}))
    with pytest.raises(ModeError, match="schema"):
        parse_units(json.dumps({"units": [{"word_ids": ["w0001"], "direction": "diagonal"}]}))


def test_parse_accepts_a_full_unit():
    payload = {
        "units": [
            {
                "word_ids": ["w0001"],
                "source_text": "價格",
                "translation": "Price",
                "direction": "vertical_rl",
                "rotated": False,
                "role": "label",
                "keep": False,
                "notes": "",
            }
        ]
    }
    parsed = parse_units(json.dumps(payload, ensure_ascii=False))
    assert parsed.units[0].direction == "vertical_rl"


def test_json_schema_is_strict_and_matches_the_pydantic_model():
    item = UNITS_SCHEMA["properties"]["units"]["items"]
    assert UNITS_SCHEMA["additionalProperties"] is False and item["additionalProperties"] is False
    assert set(item["required"]) == set(item["properties"]) == set(ClaudeUnit.model_fields)
    assert set(item["properties"]["direction"]["enum"]) == {"horizontal", "vertical_rl", "vertical_lr"}
    assert set(item["properties"]["role"]["enum"]) == {
        "title", "heading", "body", "label", "price", "number", "other",
    }  # fmt: skip
