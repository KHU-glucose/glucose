import asyncio
import hashlib
from types import SimpleNamespace

import pytest
from food_recognizer import OpenAIFoodRecognizer
from food_prompts import PROMPTS, get_food_prompt
from models import FoodItem, FoodRecognitionPayload


class FakeResponses:
    def __init__(self, payload=None):
        self.calls = []
        self.payload = payload

    async def parse(self, **kwargs):
        self.calls.append(kwargs)
        return SimpleNamespace(
            output_parsed=self.payload or FoodRecognitionPayload(
                is_food_photo=True,
                items=[
                    FoodItem(
                        name="사과",
                        food_group="간식",
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
    assert result.payload.items[0].food_group == "간식"
    assert result.meta.model == "test-model"
    assert result.meta.input_tokens == 321
    assert result.meta.output_tokens == 45
    assert responses.calls[0]["text_format"] is FoodRecognitionPayload
    user_content = responses.calls[0]["input"][1]["content"]
    assert "SNACK" in user_content[0]["text"]
    assert user_content[1]["image_url"].startswith("data:image/jpeg;base64,")
    assert user_content[1]["detail"] == "low"


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

    assert ACTIVE_PROMPT_VERSION != "glucose-v6"
    assert get_food_prompt("glucose-v6").startswith(get_food_prompt("examples-v3"))
    # Evaluation-only annotations must not become client/API output fields.
    schema = FoodRecognitionPayload.model_json_schema()
    assert "management_group" not in schema["$defs"]["FoodItem"]["properties"]
    assert "priority_food" not in schema["$defs"]["FoodItem"]["properties"]


def test_cuisine_candidate_extends_v3_without_rewriting_existing_rules():
    from food_prompts import ACTIVE_PROMPT_VERSION

    # cuisine-v7 remains opt-in; the default now addresses packaged chocolate recognition.
    assert ACTIVE_PROMPT_VERSION != "cuisine-v7"
    assert get_food_prompt("cuisine-v7").startswith(get_food_prompt("examples-v3"))
    recognizer = OpenAIFoodRecognizer(client=SimpleNamespace(responses=FakeResponses()), prompt_version="cuisine-v7")
    assert recognizer.prompt == get_food_prompt("cuisine-v7")
    for rule in ("food_group", "식기·배경·촬영 상황", "나라보다 간식 분류를 우선",
                 "주스는 food_group=null", "김밥·만두·튀김은 계속 MEAL"):
        assert rule in recognizer.prompt


def test_default_model_is_an_existing_model_id(monkeypatch):
    # `luna`처럼 존재하지 않는 ID로 바뀌면 모든 인식 호출이 404로 실패한다(2026-10-08 운영 장애).
    monkeypatch.delenv("FOOD_MODEL", raising=False)
    monkeypatch.delenv("OPENAI_MODEL", raising=False)
    assert OpenAIFoodRecognizer(api_key="test").model == "gpt-6-luna"


def packaged_payload(name="과자", product_name="자유시간", tags=None):
    return FoodRecognitionPayload(
        is_food_photo=True,
        items=[FoodItem(
            name=name, count=2, unit="팩", category_hint="SNACK",
            tags=tags if tags is not None else ["HIGH_FAT"],
            packaged_product=dict(brand="해태", product_name=product_name, volume_ml=None),
            confidence="medium",
        )],
        likely_consumed_all=None,
    )


@pytest.mark.parametrize("product_name", [
    "자유시간", "자유시간 미니", "자유 시간", "해태 자유시간", "해태제과 자유시간 미니",
])
def test_default_recognizer_corrects_known_chocolate_and_preserves_observations(product_name):
    source = packaged_payload(product_name=product_name, tags=["HIGH_FAT", "FAST_SUGAR"])
    responses = FakeResponses(source)
    recognizer = OpenAIFoodRecognizer(client=SimpleNamespace(responses=responses))

    result = asyncio.run(recognizer.recognize(b"image", "image/jpeg", "SNACK"))

    item = result.payload.items[0]
    assert item.name == "초콜릿"
    assert item.category_hint == "FAST_SUGAR"
    assert item.food_group == "간식"
    assert item.tags == ["HIGH_FAT", "FAST_SUGAR"]
    original = source.items[0]
    for field in ("packaged_product", "count", "unit", "confidence"):
        assert getattr(item, field) == getattr(original, field)
    assert original.name == "과자"
    assert result.payload.likely_consumed_all is None
    assert "자유시간" in responses.calls[0]["input"][0]["content"]


@pytest.mark.parametrize("name,product_name", [
    ("과자", None), ("과자", "자유"), ("과자", "알 수 없는 제품"),
    ("초코쿠키", "초코쿠키"), ("초코파이", "초코파이"),
    ("비스킷", "초콜릿 코팅 비스킷"), ("과자", "자유시간 쿠키"),
])
def test_chocolate_fix_does_not_relabel_other_or_unreadable_products(name, product_name):
    source = packaged_payload(name=name, product_name=product_name)
    recognizer = OpenAIFoodRecognizer(
        client=SimpleNamespace(responses=FakeResponses(source)), prompt_version="packaged-v8",
    )
    result = asyncio.run(recognizer.recognize(b"image", "image/jpeg", None))
    assert result.payload == source


def test_product_identity_in_food_name_is_also_corrected():
    source = packaged_payload(name="자유시간", product_name=None)
    source.items[0].packaged_product = None
    recognizer = OpenAIFoodRecognizer(client=SimpleNamespace(responses=FakeResponses(source)))
    result = asyncio.run(recognizer.recognize(b"image", "image/jpeg", None))
    assert result.payload.items[0].name == "초콜릿"
    assert result.payload.items[0].packaged_product is None


def test_explicit_sugar_free_label_keeps_snack_category():
    source = packaged_payload(name="무설탕 과자", tags=["HIGH_FAT", "FAST_SUGAR"])
    recognizer = OpenAIFoodRecognizer(client=SimpleNamespace(responses=FakeResponses(source)))
    result = asyncio.run(recognizer.recognize(b"image", "image/jpeg", None))
    assert result.payload.items[0].name == "무설탕 초콜릿"
    assert result.payload.items[0].category_hint == "SNACK"
    assert result.payload.items[0].tags == ["HIGH_FAT"]


def test_historical_prompt_does_not_apply_new_product_corrections():
    source = packaged_payload()
    recognizer = OpenAIFoodRecognizer(
        client=SimpleNamespace(responses=FakeResponses(source)), prompt_version="examples-v3",
    )
    result = asyncio.run(recognizer.recognize(b"image", "image/jpeg", None))
    assert result.payload == source


@pytest.mark.parametrize("name,product_name", [
    ("과자", "가나"), ("과자", "롯데 크런키"), ("과자", "킷캣"),
    ("과자", "스니커즈"), ("과자", "Kit Kat"), ("과자", "SNICKERS"),
    ("과자", "ABC 초콜릿"), ("과자", "초콜릿 코팅 비스킷"),
    ("과자", "초코파이"), ("과자", "초코칩 쿠키"),
    ("초코쿠키", None), ("초콜릿바", None), ("다크 초콜릿", None),
    ("밀크 초콜릿", None), ("화이트 초콜릿", None), ("Chocolate wafer", None),
])
def test_default_recognizer_groups_chocolate_snacks_across_products(name, product_name):
    source = packaged_payload(name=name, product_name=product_name, tags=[])
    if product_name is None:
        source.items[0].packaged_product = None
    recognizer = OpenAIFoodRecognizer(client=SimpleNamespace(responses=FakeResponses(source)))
    result = asyncio.run(recognizer.recognize(b"image", "image/jpeg", None))
    item = result.payload.items[0]
    assert item.name == "초콜릿"
    assert item.category_hint == "FAST_SUGAR"
    assert item.food_group == "간식"
    assert item.tags == ["FAST_SUGAR"]
    for field in ("packaged_product", "count", "unit", "confidence"):
        assert getattr(item, field) == getattr(source.items[0], field)


@pytest.mark.parametrize("name,product_name,category,unit,volume_ml", [
    ("과자", None, "SNACK", "팩", None),
    ("쿠키", "버터쿠키", "SNACK", "팩", None),
    ("과자", "알 수 없는 제품", "SNACK", "팩", None),
    ("과자", "가나우유", "SNACK", "팩", None),
    ("초코우유", None, "DRINK", "팩", None),
    ("초콜릿 라떼", None, "SNACK", "컵", None),
    ("과자", "초콜릿 음료", "SNACK", "팩", None),
    ("과자", "초콜릿", "SNACK", "팩", 200),
    ("초코케이크", None, "SNACK", "조각", None),
    ("초콜릿 빵", None, "SNACK", "개", None),
    ("초코 아이스크림", None, "SNACK", "개", None),
    ("Chocolate brownie", None, "SNACK", "조각", None),
    ("초콜릿 프로틴바", None, "SNACK", "개", None),
    ("밥", None, "MEAL", "공기", None),
])
def test_chocolate_snack_policy_preserves_other_foods(name, product_name, category, unit, volume_ml):
    source = packaged_payload(name=name, product_name=product_name)
    source.items[0].category_hint = category
    source.items[0].unit = unit
    source.items[0].packaged_product.volume_ml = volume_ml
    recognizer = OpenAIFoodRecognizer(client=SimpleNamespace(responses=FakeResponses(source)))
    result = asyncio.run(recognizer.recognize(b"image", "image/jpeg", None))
    assert result.payload == source


@pytest.mark.parametrize("label", ["무설탕 초콜릿", "제로 초코쿠키", "Sugar-free chocolate"])
def test_general_chocolate_policy_honors_explicit_sugar_free_labels(label):
    source = packaged_payload(product_name=label, tags=["HIGH_FAT", "FAST_SUGAR"])
    recognizer = OpenAIFoodRecognizer(client=SimpleNamespace(responses=FakeResponses(source)))
    result = asyncio.run(recognizer.recognize(b"image", "image/jpeg", None))
    assert result.payload.items[0].name == "무설탕 초콜릿"
    assert result.payload.items[0].category_hint == "SNACK"
    assert result.payload.items[0].tags == ["HIGH_FAT"]


def test_chocolate_policy_only_changes_matching_items_in_a_mixed_photo():
    source = packaged_payload(product_name="크런키")
    source.items.append(packaged_payload(name="쿠키", product_name="버터쿠키").items[0])
    recognizer = OpenAIFoodRecognizer(client=SimpleNamespace(responses=FakeResponses(source)))
    result = asyncio.run(recognizer.recognize(b"image", "image/jpeg", None))
    assert result.payload.items[0].name == "초콜릿"
    assert result.payload.items[1] == source.items[1]
    assert len(result.payload.items) == 2


def test_non_food_photo_does_not_gain_chocolate_items():
    source = FoodRecognitionPayload(is_food_photo=False, items=[], likely_consumed_all=None)
    recognizer = OpenAIFoodRecognizer(client=SimpleNamespace(responses=FakeResponses(source)))
    result = asyncio.run(recognizer.recognize(b"image", "image/jpeg", None))
    assert result.payload == source
