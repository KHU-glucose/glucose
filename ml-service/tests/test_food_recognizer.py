import asyncio
import hashlib
from types import SimpleNamespace

import pytest
from food_recognizer import OpenAIFoodRecognizer
from food_prompts import PROMPTS, get_food_prompt
from models import FoodItem, FoodRecognitionPayload


class FakeResponses:
    def __init__(self):
        self.calls = []

    async def parse(self, **kwargs):
        self.calls.append(kwargs)
        return SimpleNamespace(
            output_parsed=FoodRecognitionPayload(
                is_food_photo=True,
                items=[
                    FoodItem(
                        name="사과",
                        count=1,
                        unit="개",
                        category_hint="SNACK",
                        tags=["HIGH_CARB"],
                        packaged_product=None,
                        confidence="high",
                    )
                ],
                likely_consumed_all=True,
            ),
            usage=SimpleNamespace(input_tokens=321, output_tokens=45),
        )


def test_openai_recognizer_returns_contract_payload_and_usage():
    responses = FakeResponses()
    client = SimpleNamespace(responses=responses)
    recognizer = OpenAIFoodRecognizer(model="test-model", client=client)

    result = asyncio.run(recognizer.recognize(b"image", "image/jpeg", "SNACK"))

    assert result.payload.items[0].name == "사과"
    assert result.meta.model == "test-model"
    assert result.meta.input_tokens == 321
    assert result.meta.output_tokens == 45
    assert responses.calls[0]["text_format"] is FoodRecognitionPayload
    user_content = responses.calls[0]["input"][1]["content"]
    assert "SNACK" in user_content[0]["text"]
    assert user_content[1]["image_url"].startswith("data:image/jpeg;base64,")


@pytest.mark.parametrize("version", list(PROMPTS))
def test_selected_prompt_is_sent_without_changing_model_or_image_settings(version):
    responses = FakeResponses()
    recognizer = OpenAIFoodRecognizer(model="test-model", client=SimpleNamespace(responses=responses),
                                     prompt_version=version)
    asyncio.run(recognizer.recognize(b"image", "image/jpeg", None))
    call = responses.calls[0]
    assert call["input"][0]["content"] == get_food_prompt(version)
    assert call["model"] == "test-model"
    assert call["input"][1]["content"][1]["detail"] == "low"
    assert call["max_output_tokens"] == 1200
    assert call["store"] is False


def test_unknown_prompt_version_is_rejected_before_api_call():
    with pytest.raises(KeyError):
        OpenAIFoodRecognizer(prompt_version="unknown")


def test_baseline_matches_preserved_october_6_evaluation_prompt():
    assert hashlib.sha256(get_food_prompt("baseline-v1").encode()).hexdigest() == (
        "b8e6dd0883e7cfdc28006260eb192d2512d719f468e8c2b13c2f63d01a28b27e"
    )


def test_v3_remains_identical_to_its_first_evaluated_version():
    assert hashlib.sha256(get_food_prompt("examples-v3").encode()).hexdigest() == (
        "137628147f2a9200ce9d2d73fcb92c62de80d3bd7871b2872fc6e6938a3a2f14"
    )


def test_glucose_candidate_is_opt_in_and_preserves_output_contract():
    from food_prompts import ACTIVE_PROMPT_VERSION

    assert ACTIVE_PROMPT_VERSION == "examples-v3"
    assert get_food_prompt("glucose-v6").startswith(get_food_prompt("examples-v3"))
    # Evaluation-only annotations must not become client/API output fields.
    schema = FoodRecognitionPayload.model_json_schema()
    assert "management_group" not in schema["$defs"]["FoodItem"]["properties"]
    assert "priority_food" not in schema["$defs"]["FoodItem"]["properties"]


def test_default_model_is_an_existing_model_id(monkeypatch):
    # `luna`처럼 존재하지 않는 ID로 바뀌면 모든 인식 호출이 404로 실패한다(2026-10-08 운영 장애).
    monkeypatch.delenv("FOOD_MODEL", raising=False)
    monkeypatch.delenv("OPENAI_MODEL", raising=False)
    assert OpenAIFoodRecognizer(api_key="test").model == "gpt-6-luna"
