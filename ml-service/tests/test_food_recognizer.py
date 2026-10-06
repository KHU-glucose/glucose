import asyncio
from types import SimpleNamespace

from food_recognizer import OpenAIFoodRecognizer
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
