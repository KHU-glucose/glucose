"""Schema/compatibility checks only; these do not measure image accuracy."""
import pytest
from openai.lib._pydantic import to_strict_json_schema
from pydantic import ValidationError

from models import FoodItem, FoodRecognitionPayload


def item(name="김치찌개", category_hint="MEAL", count=None, **extra):
    return dict(name=name, count=count, unit="개", category_hint=category_hint,
                tags=[], packaged_product=None, confidence="high", **extra)


@pytest.mark.parametrize("name,group,hint,count", [
    ("김치찌개", "한식", "MEAL", None),
    ("초밥", "일식", "MEAL", None),
    ("짜장면", "중식", "MEAL", None),
    ("쿠키", "간식", "SNACK", 3),
    ("초콜릿", "간식", "FAST_SUGAR", 2),
    ("약과", "간식", "SNACK", 1),
    ("피자", None, "MEAL", None),
    ("볶음밥", None, "MEAL", None),
    ("주스", None, "FAST_SUGAR", 1),
    ("우유", None, "DRINK", 1),
    ("맥주", None, "ALCOHOL", 1),
])
def test_group_is_separate_from_name_count_and_category(name, group, hint, count):
    result = FoodItem.model_validate(item(name, hint, count, food_group=group)).model_dump()
    assert result["name"] == name
    assert result["food_group"] == group
    assert result["category_hint"] == hint
    assert result["count"] == count


def test_legacy_reports_without_group_still_parse_and_serialize_null():
    payload = FoodRecognitionPayload.model_validate(dict(
        is_food_photo=True, items=[item()], likely_consumed_all=None))
    assert payload.model_dump()["items"][0]["food_group"] is None


@pytest.mark.parametrize("group", ["양식", "KOREAN", "MEAL", "", 12])
def test_group_rejects_values_outside_contract(group):
    with pytest.raises(ValidationError):
        FoodItem.model_validate(item(food_group=group))


def test_openai_strict_schema_requires_group_but_allows_null():
    # The SDK requires all fields even though Pydantic tolerates missing legacy fields.
    schema = to_strict_json_schema(FoodRecognitionPayload)["$defs"]["FoodItem"]
    assert "food_group" in schema["required"]
    assert schema["additionalProperties"] is False
    choices = schema["properties"]["food_group"]["anyOf"]
    assert {"type": "null"} in choices
    assert next(choice["enum"] for choice in choices if "enum" in choice) == [
        "한식", "일식", "중식", "간식"]


def test_nonfood_remains_empty_list_without_a_picture_level_group():
    payload = FoodRecognitionPayload(is_food_photo=False, items=[], likely_consumed_all=None)
    assert payload.model_dump() == dict(is_food_photo=False, items=[], likely_consumed_all=None)
