"""Offline tests: separating name and glucose-recording scores cannot inflate either."""
import copy

import pytest

from eval_food import assess_targets, print_accuracy_summary, score_cases, summarize_accuracy
from food_management import load_management_policy


def case(items, predicted=None, *, complete=False, group_complete=False):
    return {"file": "synthetic.jpg", "expected": {
        "is_food_photo": bool(items), "items": items,
        "annotation_complete": complete, "management_annotation_complete": group_complete},
        "prediction": {"is_food_photo": True, "items": predicted} if predicted is not None else None,
        "latency_ms": 100, "meta": {"input_tokens": 0, "output_tokens": 0}}


def fish():
    return {"name": "갈치구이", "management_group": "FISH_PLAIN_GRILLED_REFERENCE"}


def test_same_reference_group_only_changes_group_score_not_name_or_target():
    rows = [case([fish()], [{"name": "고등어구이"}], complete=True, group_complete=True)]
    metrics = score_cases(rows)
    before = copy.deepcopy(metrics)
    summary = summarize_accuracy(rows, metrics)
    assert summary["food_name"]["rate"] == 0
    assert summary["glucose_management"]["rate"] == 1
    assert summary["glucose_management"]["precision"]["rate"] == 1
    assert summary["glucose_management"]["clinical_glucose_prediction"] is False
    assert metrics == before
    assert assess_targets(metrics)["food_name_recall"]["status"] == "FAIL"
    assert "management_group_recall" not in assess_targets(metrics)


def test_source_name_labels_do_not_infer_glucose_truth_even_for_known_catalog_food():
    rows = [case([{"name": "갈치구이"}], [{"name": "갈치구이"}])]
    summary = summarize_accuracy(rows)
    assert summary["food_name"]["rate"] == 1
    group = summary["glucose_management"]
    assert group["rate"] is None and group["status"] == "NOT_MEASURED"
    assert group["label_coverage"] == {"correct": 0, "total": 1, "rate": 0.0}
    assert group["unlabelled_food_items"] == 1


def test_partial_coverage_and_precision_scope_are_explicit():
    rows = [case([fish(), {"name": "국수"}], [{"name": "고등어구이"}, {"name": "국수"}])]
    summary = summarize_accuracy(rows)
    assert summary["food_name"]["rate"] == 0.5
    assert summary["food_name"]["precision"]["rate"] is None
    assert summary["food_name"]["f1"] is None
    group = summary["glucose_management"]
    assert group["rate"] == 1
    assert group["status"] == "MEASURED_LABELLED_SUBSET"
    assert group["label_coverage"] == {"correct": 1, "total": 2, "rate": 0.5}
    assert group["labelled_images"] == 1 and group["fully_annotated_images"] == 0
    assert group["precision"]["rate"] is None


@pytest.mark.parametrize("predicted", [None, [], [{"name": "확인 불가"}], [{"name": "데리야키 생선구이"}]])
def test_failed_missing_unknown_and_wrong_predictions_stay_in_denominator(predicted):
    summary = summarize_accuracy([case([fish()], predicted)])
    assert summary["food_name"]["correct"] == 0 and summary["food_name"]["total"] == 1
    assert summary["glucose_management"]["correct"] == 0 and summary["glucose_management"]["total"] == 1
    assert summary["glucose_management"]["rate"] == 0
    assert summary["glucose_management"]["label_coverage"]["rate"] == 1


def test_parent_credit_is_not_added_to_group_accuracy():
    summary = summarize_accuracy([case([fish()], [{"name": "생선구이"}])])
    assert summary["glucose_management"]["rate"] == 0
    assert summary["glucose_management"]["parent_only"]["rate"] == 1


def test_complete_truth_allows_precision_and_extra_food_penalty():
    summary = summarize_accuracy([case([fish()], [{"name": "갈치구이"}, {"name": "사과"}],
                                      complete=True, group_complete=True)])
    assert summary["food_name"]["precision"]["rate"] == 0.5
    assert summary["food_name"]["f1"] == 0.6667
    assert summary["glucose_management"]["precision"]["rate"] == 0.5


@pytest.mark.parametrize("rows", [[], [case([], [], complete=True, group_complete=True)]])
def test_empty_or_nonfood_only_has_no_food_or_group_accuracy(rows):
    summary = summarize_accuracy(rows)
    assert summary["food_name"]["rate"] is None
    assert summary["glucose_management"]["rate"] is None
    assert summary["glucose_management"]["label_coverage"]["rate"] is None


def test_custom_policy_version_is_used_in_score_and_summary():
    policy = load_management_policy().model_copy(update={"version": "custom-evaluation-v1"})
    summary = summarize_accuracy([case([fish()], [{"name": "고등어구이"}])], policy=policy)
    assert summary["glucose_management"]["policy_version"] == "custom-evaluation-v1"
    assert summary["glucose_management"]["rate"] == 1


def test_two_axis_console_shows_missing_truth_and_coverage(capsys):
    print_accuracy_summary(summarize_accuracy([case([{"name": "김밥"}], [{"name": "김밥"}])]))
    output = capsys.readouterr().out
    assert "음식명 정확도" in output and "100.0% (1/1)" in output
    assert "혈당 관리용 특성 정확도" in output and "미측정 (정답 없음)" in output
    assert "0.0% (0/1개 음식)" in output
    assert "실제 혈당 영향의 정확도가 아닙니다" in output
