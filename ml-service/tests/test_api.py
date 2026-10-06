import io

from fastapi.testclient import TestClient
from PIL import Image

from food_recognizer import FoodRecognitionResult
from main import app, get_food_recognizer
from models import FoodItem, FoodRecognitionMeta, FoodRecognitionPayload


class FakeFoodRecognizer:
    async def recognize(self, image_bytes, image_mime_type, context):
        assert image_bytes
        assert image_mime_type == "image/jpeg"
        return FoodRecognitionResult(
            payload=FoodRecognitionPayload(
                is_food_photo=True,
                items=[
                    FoodItem(
                        name="김밥",
                        count=1,
                        unit="개",
                        category_hint="MEAL",
                        tags=["HIGH_CARB"],
                        packaged_product=None,
                        confidence="high",
                    )
                ],
                likely_consumed_all=True,
            ),
            meta=FoodRecognitionMeta(
                model="fake",
                latency_ms=12,
                input_tokens=10,
                output_tokens=5,
            ),
        )


def jpeg_bytes() -> bytes:
    output = io.BytesIO()
    Image.new("RGB", (1200, 600), "white").save(output, "JPEG")
    return output.getvalue()


def test_health_and_food_endpoint(monkeypatch):
    monkeypatch.setenv("INTERNAL_TOKEN", "test-token")
    app.dependency_overrides[get_food_recognizer] = lambda: FakeFoodRecognizer()
    try:
        with TestClient(app) as client:
            health = client.get("/health")
            response = client.post(
                "/v1/food/recognize",
                headers={
                    "X-Internal-Token": "test-token",
                    "X-Request-Id": "request-123",
                },
                files={"image": ("meal.jpg", jpeg_bytes(), "image/jpeg")},
                data={"context": "MEAL"},
            )
    finally:
        app.dependency_overrides.clear()

    assert health.json() == {"status": "ok", "version": "1.0.0"}
    assert response.status_code == 200
    assert response.headers["X-Request-Id"] == "request-123"
    assert response.json()["items"][0]["name"] == "김밥"
    assert response.json()["meta"]["model"] == "fake"


def test_food_endpoint_rejects_bad_token(monkeypatch):
    monkeypatch.setenv("INTERNAL_TOKEN", "test-token")
    with TestClient(app) as client:
        response = client.post(
            "/v1/food/recognize",
            headers={"X-Internal-Token": "wrong"},
            files={"image": ("meal.jpg", jpeg_bytes(), "image/jpeg")},
        )

    assert response.status_code == 401
    assert response.json()["code"] == "UNAUTHORIZED"
    assert response.json()["request_id"] == response.headers["X-Request-Id"]
