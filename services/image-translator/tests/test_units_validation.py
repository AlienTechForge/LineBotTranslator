"""Schema validation and the unknown / duplicate word-id rules."""

import json

import pytest
from pydantic import ValidationError

from app.claude_units import UNITS_SCHEMA, parse_units, validate_units
from app.models import ClaudeUnit, ClaudeUnits, ModeError

VALID = {"w0001", "w0002", "w0003", "w0004"}


def unit(ids, **kw):
    base = dict(
        source_text="s", translation="t", direction="horizontal", rotated=False, role="body", keep=False, notes="",
        short="", align="left", weight="regular", importance="major",
    )  # fmt: skip
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
                "short": "Price",
                "align": "center",
                "weight": "bold",
                "importance": "minor",
            }
        ]
    }
    parsed = parse_units(json.dumps(payload, ensure_ascii=False))
    assert parsed.units[0].direction == "vertical_rl"
    first = parsed.units[0]
    assert (first.short, first.align, first.weight, first.importance) == ("Price", "center", "bold", "minor")


def test_the_four_planner_fields_have_defaults_for_hand_built_units_but_their_enums_are_checked():
    first = unit(["w0001"]).model_copy()
    assert (first.short, first.align, first.weight, first.importance) == ("", "left", "regular", "major")
    full = {"word_ids": ["w0001"], "source_text": "a", "translation": "b"}
    for field, bad in (("align", "justify"), ("weight", "heavy"), ("importance", "medium"), ("role", "caption")):
        with pytest.raises(ModeError, match="schema"):
            parse_units(json.dumps({"units": [{**full, field: bad}]}))
    for field, good in (("align", "right"), ("weight", "bold"), ("importance", "minor")):
        assert getattr(parse_units(json.dumps({"units": [{**full, field: good}]})).units[0], field) == good


def test_unknown_fields_are_rejected_not_ignored():
    with pytest.raises(ModeError, match="schema"):
        parse_units(json.dumps({"units": [{"word_ids": ["w0001"], "font_size": 12}]}))
    with pytest.raises(ModeError, match="schema"):
        parse_units(json.dumps({"units": [], "extra": 1}))
    with pytest.raises(ValidationError):
        ClaudeUnit.model_validate({"word_ids": ["w0001"], "frame": [[0, 0]]})  # the planner never gives coordinates


def test_validate_units_keeps_the_new_fields_of_the_units_it_accepts():
    units = ClaudeUnits(units=[unit(["w0001"], short="Hi", align="right", weight="bold", importance="minor"), unit(["w9999"])])
    (accepted,), notes = validate_units(units, VALID)
    assert (accepted.short, accepted.align, accepted.weight, accepted.importance) == ("Hi", "right", "bold", "minor")
    assert len(notes) == 1


def test_json_schema_is_strict_and_matches_the_pydantic_model():
    item = UNITS_SCHEMA["properties"]["units"]["items"]
    assert UNITS_SCHEMA["additionalProperties"] is False and item["additionalProperties"] is False
    assert set(item["required"]) == set(item["properties"]) == set(ClaudeUnit.model_fields)
    assert set(item["properties"]["direction"]["enum"]) == {"horizontal", "vertical_rl", "vertical_lr"}
    assert set(item["properties"]["role"]["enum"]) == {
        "title", "heading", "body", "label", "price", "number", "other",
    }  # fmt: skip
    for field in ("short", "align", "weight", "importance"):
        assert field in item["required"], field  # all four are required in the schema
    assert item["properties"]["short"]["type"] == "string"
    assert item["properties"]["align"]["enum"] == ["left", "center", "right"]
    assert item["properties"]["weight"]["enum"] == ["regular", "bold"]
    assert item["properties"]["importance"]["enum"] == ["major", "minor"]
