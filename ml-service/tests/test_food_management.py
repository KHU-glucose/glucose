import copy
import json

import pytest
from PIL import Image

from eval_food import load_dataset, score_cases
from food_management import ManagementPolicy, load_management_policy, policy_evidence


def row(expected, names, *, complete=False, prediction=True):
    return {
        "file": "synthetic-fixture.jpg",
        "expected": {"is_food_photo": True, "items": expected,
                     "management_annotation_complete": complete},
        "prediction": {"is_food_photo": True, "items": names} if prediction else None,
        "latency_ms": 100,
        "meta": {"input_tokens": 0, "output_tokens": 0},
    }


def fish(**kwargs):
    return {"name": "갈치구이", "management_group": "FISH_PLAIN_GRILLED_REFERENCE", **kwargs}


def test_group_match_does_not_change_exact_name_or_completion_targets():
    from eval_food import assess_targets
    metrics = score_cases([row([fish()], [{"name": "고등어구이"}], complete=True)])
    assert metrics["food_name_recall"]["rate"] == 0
    assert metrics["management_group_recall"]["rate"] == 1
    assert metrics["management_group_precision"]["rate"] == 1
    assert assess_targets(metrics)["food_name_recall"]["status"] == "FAIL"
    assert "management_group_recall" not in assess_targets(metrics)


def test_broad_parent_is_partial_not_full_nutritional_match():
    metrics = score_cases([row([fish(requires_uncertainty=True)],
                              [{"name": "생선구이", "confidence": "low"}])])
    assert metrics["management_group_recall"]["rate"] == 0
    assert metrics["management_parent_only_rate"]["rate"] == 1
    assert metrics["management_unresolved_prediction_rate"]["rate"] == 1
    assert metrics["uncertainty_low_confidence_recall"]["rate"] == 1


def test_sweet_glaze_is_a_separate_group_and_conflict_is_reported():
    metrics = score_cases([row([fish(priority_food=True)], [{"name": "데리야키 생선구이"}])])
    assert metrics["management_group_recall"]["rate"] == 0
    assert metrics["management_confusion_rate"]["rate"] == 1
    assert metrics["priority_food_miss_rate"]["rate"] == 1
    assert metrics["management_case_details"][0]["confusions"][0]["predicted_group"] == "FISH_SWEET_GLAZED"


def test_correct_mixed_foods_are_not_misdiagnosed_as_confusions():
    expected = [fish(), {"name": "데리야키 생선구이", "management_group": "FISH_SWEET_GLAZED"}]
    metrics = score_cases([row(expected, [{"name": "데리야키 생선구이"}])])
    assert metrics["management_group_recall"]["rate"] == 0.5
    assert metrics["management_confusion_rate"]["rate"] == 0
    assert metrics["management_parent_only_rate"]["rate"] == 0


def test_correct_unannotated_food_is_reserved_before_conflict_detection():
    metrics = score_cases([row([fish(), {"name": "데리야키 생선구이"}],
                              [{"name": "데리야키 생선구이"}])])
    assert metrics["management_confusion_rate"]["rate"] == 0


def test_one_prediction_cannot_cover_two_group_labels():
    metrics = score_cases([row([fish(), fish()], [{"name": "갈치구이 정식"}, {"name": "고등어구이"}])])
    assert metrics["management_group_recall"] == {"correct": 1, "total": 2, "rate": 0.5}


def test_no_substring_tags_or_category_shortcuts():
    policy = load_management_policy()
    assert policy.resolve(" 고등어 구이 ") == "FISH_PLAIN_GRILLED_REFERENCE"
    for name in ["고등어 양념구이", "고등어구이 정식", "고등어구이무설탕", "콜라", "밥"]:
        assert policy.resolve(name) is None
    metrics = score_cases([row([fish()], [{"name": "알 수 없음", "tags": ["FAST_SUGAR"],
                                         "category_hint": "FAST_SUGAR"}])])
    assert metrics["management_group_recall"]["rate"] == 0


def test_generic_cola_is_unresolved_and_regular_zero_confusion_is_counted():
    truth = {"name": "제로 콜라", "management_group": "ZERO_COLA"}
    metrics = score_cases([row([truth], [{"name": "콜라"}]),
                           row([truth], [{"name": "일반 콜라"}])])
    assert metrics["management_group_recall"]["rate"] == 0
    assert metrics["management_parent_only_rate"]["rate"] == 0.5
    assert metrics["management_confusion_rate"]["rate"] == 0.5


def test_failures_and_contradictory_false_stay_in_group_and_priority_denominators():
    rows = [row([fish(priority_food=True, requires_uncertainty=True)], [], prediction=False),
            row([fish(priority_food=True)], [{"name": "고등어구이"}], complete=True)]
    rows[1]["prediction"]["is_food_photo"] = False
    metrics = score_cases(rows)
    assert metrics["management_group_recall"]["rate"] == 0
    assert metrics["management_group_precision"] == {"correct": 0, "total": 1, "rate": 0.0}
    assert metrics["priority_food_miss_rate"]["rate"] == 1
    assert metrics["uncertainty_low_confidence_recall"]["rate"] == 0


def test_priority_names_work_without_group_and_uncertainty_requires_low_confidence():
    metrics = score_cases([row([
        {"name": "국수", "priority_food": True},
        {"name": "생선구이", "requires_uncertainty": True}],
        [{"name": "생선구이", "confidence": "high"}])])
    assert metrics["priority_food_miss_rate"]["rate"] == 1
    assert metrics["uncertainty_low_confidence_recall"]["rate"] == 0
    assert metrics["management_group_recall"]["rate"] is None


def test_incomplete_group_annotation_does_not_claim_precision():
    metrics = score_cases([row([fish()], [{"name": "고등어구이"}, {"name": "밥"}])])
    assert metrics["management_group_recall"]["rate"] == 1
    assert metrics["management_group_precision"]["rate"] is None
    assert score_cases([])["management_group_recall"]["rate"] is None


def test_extra_unknown_prediction_penalizes_precision_on_fully_annotated_image():
    metrics = score_cases([row([fish()], [{"name": "고등어구이"}, {"name": "사과"}], complete=True)])
    assert metrics["management_group_precision"]["rate"] == 0.5


@pytest.mark.parametrize("bad", ["unknown_id", "mismatched_name", "incomplete_truth", "string_boolean"])
def test_bad_annotations_fail_before_any_api_call(tmp_path, bad):
    Image.new("RGB", (16, 16)).save(tmp_path / "sample.jpg")
    label = {"file": "sample.jpg", "is_food_photo": True, "items": [fish()]}
    if bad == "unknown_id":
        label["items"][0]["management_group"] = "TYPO"
    elif bad == "mismatched_name":
        label["items"][0]["management_group"] = "ZERO_COLA"
    elif bad == "incomplete_truth":
        label["management_annotation_complete"] = True
    else:
        label["items"][0]["priority_food"] = "true"
    path = tmp_path / "labels.json"
    path.write_text(json.dumps([label]), encoding="utf-8")
    with pytest.raises(ValueError):
        load_dataset(path, tmp_path)


@pytest.mark.parametrize("bad", ["duplicate_name", "duplicate_group", "parent_collision", "unknown_pair", "missing_reference", "wrong_basis"])
def test_policy_rejects_ambiguous_or_unsupported_definitions(bad):
    data = load_management_policy().model_dump(mode="json")
    if bad == "duplicate_name":
        data["groups"][1]["names"].append("갈치 구이")
    elif bad == "duplicate_group":
        data["groups"].append(copy.deepcopy(data["groups"][0]))
    elif bad == "parent_collision":
        data["groups"][1]["parent_names"].append("갈치구이")
    elif bad == "unknown_pair":
        data["confusion_pairs"].append(["UNKNOWN", "ZERO_COLA"])
    elif bad == "missing_reference":
        data["groups"][0]["nutrition_reference"] = None
    else:
        data["groups"][0]["nutrition_reference"]["basis_g"] = 250
    with pytest.raises(ValueError):
        ManagementPolicy.model_validate(data)


def test_policy_snapshot_hash_changes_with_membership_and_preserves_source():
    policy = load_management_policy()
    evidence = policy_evidence(policy)
    assert evidence["sha256"] == policy_evidence(policy)["sha256"]
    data = policy.model_dump(mode="json")
    data["groups"][1]["names"].append("추가 실험 이름")
    assert evidence["sha256"] != policy_evidence(ManagementPolicy.model_validate(data))["sha256"]
    reference = evidence["snapshot"]["groups"][0]["nutrition_reference"]
    assert reference["basis_g"] == 100
    assert reference["entries"][0]["carbohydrate_g"] == 0.1
    assert reference["entries"][1]["sugars_g"] == 0.1
