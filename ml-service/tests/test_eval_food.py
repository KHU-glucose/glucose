import asyncio
import json

import pytest
from PIL import Image

from eval_food import load_dataset, matching_count, names_match, score_cases


def case(expected, prediction, is_food=True):
    return {
        "expected": {"is_food_photo": is_food, "items": expected},
        "prediction": prediction,
        "latency_ms": 100,
        "meta": {"input_tokens": 10, "output_tokens": 5},
    }


def test_scoring_penalizes_missing_and_extra_foods_and_wrong_count():
    rows = [case(
        [{"name": "초콜릿", "count": 3, "unit": "조각"}, {"name": "주스"}],
        {"is_food_photo": True, "items": [
            {"name": "초콜릿", "count": 2, "unit": "조각"},
            {"name": "사과", "count": 1, "unit": "개"},
        ]},
    )]
    metrics = score_cases(rows)
    assert metrics["food_name_recall"]["rate"] == 0.5
    assert metrics["food_name_precision"]["rate"] == 0.5
    assert metrics["count_and_unit_accuracy"]["rate"] == 0


def test_duplicate_prediction_cannot_score_two_foods():
    expected = [{"name": "사과"}, {"name": "사과"}]
    assert matching_count(expected, [{"name": "사과"}], names_match) == 1


def test_aliases_use_maximum_matching_and_ignore_spacing():
    expected = [{"name": "밥", "aliases": ["흰 쌀밥"]}, {"name": "흰쌀밥"}]
    predicted = [{"name": "흰쌀밥"}, {"name": "밥"}]
    assert matching_count(expected, predicted, names_match) == 2


def test_failure_stays_in_accuracy_denominators():
    metrics = score_cases([case(
        [{"name": "사과", "count": 1, "unit": "개"}], None,
    )])
    assert metrics["food_name_recall"] == {"correct": 0, "total": 1, "rate": 0.0}
    assert metrics["count_and_unit_accuracy"]["rate"] == 0
    assert metrics["food_photo_accuracy"]["rate"] == 0
    assert metrics["successful_response_rate"]["rate"] == 0
    assert metrics["median_success_latency_ms"] is None


def test_wrong_unit_does_not_pass_count_accuracy():
    metrics = score_cases([case(
        [{"name": "주스", "count": 1, "unit": "팩"}],
        {"is_food_photo": True, "items": [{"name": "주스", "count": 1, "unit": "컵"}]},
    )])
    assert metrics["food_name_recall"]["rate"] == 1
    assert metrics["count_and_unit_accuracy"]["rate"] == 0


def test_nonfood_success_failure_and_contradictory_output():
    rows = [
        case([], {"is_food_photo": False, "items": []}, False),
        case([], None, False),
        case([], {"is_food_photo": False, "items": [{"name": "밥"}]}, False),
    ]
    metrics = score_cases(rows)
    assert metrics["nonfood_rejection_rate"] == {"correct": 1, "total": 3, "rate": 0.3333}
    assert metrics["count_and_unit_accuracy"]["rate"] is None


def write_labels(tmp_path, rows):
    path = tmp_path / "labels.json"
    path.write_text(json.dumps(rows, ensure_ascii=False), encoding="utf-8")
    return path


def test_dataset_checks_images_before_any_ai_call(tmp_path):
    Image.new("RGB", (16, 16), "white").save(tmp_path / "sample.jpg")
    path = write_labels(tmp_path, [{"file": "sample.jpg", "is_food_photo": False, "items": []}])
    assert len(load_dataset(path, tmp_path)) == 1


@pytest.mark.parametrize("file", ["missing.jpg", "../outside.jpg"])
def test_dataset_rejects_missing_or_escaped_paths(tmp_path, file):
    path = write_labels(tmp_path, [{"file": file, "is_food_photo": False, "items": []}])
    with pytest.raises(ValueError):
        load_dataset(path, tmp_path)


def test_count_labels_require_units(tmp_path):
    path = write_labels(tmp_path, [{
        "file": "sample.jpg", "is_food_photo": True,
        "items": [{"name": "사과", "count": 1}],
    }])
    with pytest.raises(ValueError, match="unit"):
        load_dataset(path, tmp_path)


def test_runner_prepares_real_images_and_saves_scores_without_sending_labels(tmp_path, monkeypatch):
    import food_recognizer
    from eval_food import Label, run_evaluation
    from food_recognizer import FoodRecognitionResult
    from models import FoodItem, FoodRecognitionMeta, FoodRecognitionPayload

    class FakeRecognizer:
        api_key = "test-key"
        model = "fake-model"
        _client = None

        def __init__(self, model=None):
            pass

        async def recognize(self, image_bytes, mime, context):
            from io import BytesIO

            with Image.open(BytesIO(image_bytes)) as prepared:
                assert prepared.size == (768, 384)
            assert mime == "image/jpeg"
            assert context is None
            return FoodRecognitionResult(
                FoodRecognitionPayload(
                    is_food_photo=True,
                    items=[FoodItem(
                        name="사과", count=2, unit="개", category_hint="SNACK",
                        tags=[], packaged_product=None, confidence="high",
                    )],
                    likely_consumed_all=None,
                ),
                FoodRecognitionMeta(model="fake-model", latency_ms=1, input_tokens=2, output_tokens=3),
            )

    monkeypatch.setattr(food_recognizer, "OpenAIFoodRecognizer", FakeRecognizer)
    Image.new("RGB", (1200, 600), "white").save(tmp_path / "sample.jpg")
    labels = [Label.model_validate({
        "file": "sample.jpg", "is_food_photo": True,
        "items": [{"name": "사과", "count": 2, "unit": "개"}],
    })]
    output = tmp_path / "report.json"
    report = {"completed": False}
    asyncio.run(run_evaluation(labels, tmp_path, report, output, None))
    saved = json.loads(output.read_text(encoding="utf-8"))
    assert saved["completed"] is True
    assert saved["metrics"]["count_and_unit_accuracy"]["rate"] == 1
    assert saved["metrics"]["reported_input_tokens"] == 2
    assert saved["prompt_sha256"]
    assert saved["cases"][0]["image_sha256"]

    with pytest.raises(FileExistsError):
        asyncio.run(run_evaluation(labels, tmp_path, {}, output, None))
