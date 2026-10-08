"""Synthetic offline fixtures; invented numbers are not real food nutrition data."""

import copy
import json

import pytest

from eval_food import assess_targets, print_accuracy_summary, score_cases, summarize_accuracy
from eval_food_family import rescore_reports
from food_family import (FamilyPolicy, compare_reference_pair, family_policy_evidence,
                         load_family_policy, score_family_cases)


def fixture_policy():
    data = load_family_policy().model_dump(mode="json")
    data["version"] = "synthetic-tests-only"
    data["nutrition"] = []
    for name, carbs, sugars in [("떡볶이", 20, 3), ("치즈떡볶이", 25, 5),
                                ("계란찜", 20, 3), ("죽", 20, 3), ("김밥", 0, 0)]:
        data["nutrition"].append({"name": name, "basis_g": 100, "carbohydrate_g": carbs,
            "sugars_g": sugars, "source_url": "https://example.com/synthetic-fixture",
            "source_title": "Invented test data", "source_locator": name,
            "review_status": "VERIFIED", "limitation": "Synthetic test only, not real nutrition"})
    return FamilyPolicy.model_validate(data)


def row(expected, predicted):
    return {"file": "synthetic.jpg", "expected": {"file": "synthetic.jpg", "is_food_photo": True,
        "items": [{"name": name} for name in expected]},
        "prediction": {"is_food_photo": True, "items": [{"name": name} for name in predicted]}
            if predicted is not None else None,
        "latency_ms": 1, "meta": {"input_tokens": 0, "output_tokens": 0}}


def test_same_family_is_gate_not_exact_name_or_nutrition_pass():
    cases = [row(["떡볶이"], ["치즈떡볶이"])]
    original = copy.deepcopy(cases)
    metrics = score_cases(cases)
    summary = summarize_accuracy(cases, metrics, family_policy=fixture_policy())
    family = summary["family_gated_nutrition"]
    assert summary["food_name"]["rate"] == 0 and family["food_family"]["rate"] == 1
    nutrition = family["within_family_nutrition"]
    assert nutrition["nutrient_errors"]["carbohydrate_g"]["mean_absolute_difference_g_per_100g"] == 5
    assert nutrition["nutrient_errors"]["sugars_g"]["mean_absolute_difference_g_per_100g"] == 2
    assert nutrition["similarity_pass_threshold"] is None
    assert nutrition["name_changed_compared_pairs"] == 1
    assert assess_targets(metrics)["food_name_recall"]["status"] == "FAIL"
    assert cases == original


def test_identical_nutrients_in_different_families_cannot_be_compared():
    policy = fixture_policy()
    assert compare_reference_pair("계란찜", "죽", policy)["status"] == "DIFFERENT_FAMILY"
    score = score_family_cases([row(["계란찜"], ["죽"])], policy)
    assert score["food_family"]["rate"] == 0
    assert score["within_family_nutrition"]["compared_pairs"] == 0
    assert score["case_details"][0]["unmatched_expected"][0]["status"] == "NO_SAME_FAMILY_PREDICTION"


@pytest.mark.parametrize("predicted", [None, [], ["죽"], ["확인불가"]])
def test_failure_missing_wrong_unknown_stay_in_family_denominator(predicted):
    score = score_family_cases([row(["떡볶이"], predicted)], fixture_policy())
    assert score["food_family"]["correct"] == 0
    assert score["food_family"]["total"] == 1
    assert score["within_family_nutrition"]["status"] == "NOT_MEASURED"


def test_unknown_truth_is_not_inferred_from_aliases_tags_or_predicted_family():
    cases = [row(["기타"], ["치즈떡볶이"])]
    cases[0]["expected"]["items"][0]["aliases"] = ["떡볶이"]
    score = score_family_cases(cases, fixture_policy())
    assert score["food_family"]["rate"] is None
    assert score["food_family"]["label_coverage"]["rate"] == 0
    assert load_family_policy().resolve("떡볶이 정식") is None
    assert load_family_policy().resolve(" 치즈 떡볶이 ") == "TTEOKBOKKI"


def test_missing_reference_is_not_zero_difference_or_cross_family_fallback():
    score = score_family_cases([row(["김밥"], ["참치김밥"])], fixture_policy())
    assert score["food_family"]["rate"] == 1
    nutrient = score["within_family_nutrition"]
    assert nutrient["missing_reference_pairs"] == 1 and nutrient["comparison_coverage"]["rate"] == 0
    assert nutrient["nutrient_errors"]["sugars_g"]["mean_absolute_difference_g_per_100g"] is None


def test_missing_value_and_genuine_zero_are_distinct():
    policy = fixture_policy()
    assert compare_reference_pair("김밥", "김밥", policy)["status"] == "COMPARED"
    data = policy.model_dump(mode="json")
    data["nutrition"][0]["sugars_g"] = None
    score = score_family_cases([row(["떡볶이"], ["치즈떡볶이"])], FamilyPolicy.model_validate(data))
    assert score["within_family_nutrition"]["missing_value_pairs"] == 1
    assert score["within_family_nutrition"]["compared_pairs"] == 0


def test_contradictory_false_has_no_family_or_nutrition_credit():
    case = row(["떡볶이"], ["떡볶이"])
    case["prediction"]["is_food_photo"] = False
    assert score_family_cases([case])["food_family"]["rate"] == 0


def test_exact_pairing_first_not_best_nutrient_similarity():
    case = row(["떡볶이", "치즈떡볶이"], ["치즈떡볶이", "떡볶이"])
    score = score_family_cases([case], fixture_policy())
    pairs = score["case_details"][0]["pairs"]
    assert {(p["expected_index"], p["predicted_index"]) for p in pairs} == {(0, 1), (1, 0)}
    assert score["within_family_nutrition"]["name_changed_compared_pairs"] == 0
    assert score["within_family_nutrition"]["name_changed_nutrient_errors"]["sugars_g"]["mean_absolute_difference_g_per_100g"] is None


def test_one_prediction_cannot_cover_two_labels_and_ambiguous_pairing_is_deferred():
    score = score_family_cases([row(["떡볶이", "떡볶이"], ["치즈떡볶이"])], fixture_policy())
    assert score["food_family"]["rate"] == 0.5
    assert score["within_family_nutrition"]["ambiguous_pairing_matches"] == 1
    assert score["within_family_nutrition"]["compared_pairs"] == 0


def test_extra_same_family_prediction_is_ambiguous_not_best_pair_selected():
    score = score_family_cases([row(["떡볶이"], ["치즈떡볶이", "치즈떡볶이"])], fixture_policy())
    assert score["food_family"]["correct"] == 1
    assert score["within_family_nutrition"]["ambiguous_pairing_matches"] == 1


def test_partial_catalog_and_reference_coverage_include_all_truth():
    cases = [row(["떡볶이", "김밥", "국수"], ["치즈떡볶이", "참치김밥", "국수"])]
    score = score_family_cases(cases, fixture_policy())
    assert score["food_family"]["label_coverage"] == {"correct": 2, "total": 3, "rate": 0.6667}
    assert score["within_family_nutrition"]["comparison_coverage"]["rate"] == 0.5
    assert score["within_family_nutrition"]["coverage_of_all_expected"]["rate"] == 0.3333


@pytest.mark.parametrize("bad", ["duplicate_family", "duplicate_name", "empty_name", "wrong_basis", "negative",
                                 "nan", "infinite", "missing_source", "unreviewed", "duplicate_reference", "unknown_reference"])
def test_invalid_or_unverified_policy_cannot_score(bad):
    data = fixture_policy().model_dump(mode="json")
    if bad == "duplicate_family":
        data["families"].append(copy.deepcopy(data["families"][0]))
    elif bad == "duplicate_name":
        data["families"][1]["names"].append("떡 볶이")
    elif bad == "empty_name":
        data["families"][0]["names"].append(" ")
    elif bad == "wrong_basis":
        data["nutrition"][0]["basis_g"] = 200
    elif bad in {"negative", "nan", "infinite"}:
        data["nutrition"][0]["sugars_g"] = {"negative": -1, "nan": float("nan"), "infinite": float("inf")}[bad]
    elif bad == "missing_source":
        del data["nutrition"][0]["source_url"]
    elif bad == "unreviewed":
        data["nutrition"][0]["review_status"] = "LLM_DRAFT"
    elif bad == "duplicate_reference":
        data["nutrition"].append(copy.deepcopy(data["nutrition"][0]))
    else:
        data["nutrition"][0]["name"] = "미등록 음식"
    with pytest.raises(ValueError):
        FamilyPolicy.model_validate(data)


def test_policy_hash_changes_with_catalog_or_reference():
    policy = fixture_policy()
    first = family_policy_evidence(policy)
    data = policy.model_dump(mode="json")
    data["nutrition"][0]["carbohydrate_g"] += 1
    assert first["sha256"] != family_policy_evidence(FamilyPolicy.model_validate(data))["sha256"]


def test_offline_rescore_preserves_sources_refuses_overwrite_and_duplicate_photos(tmp_path):
    policy_path = tmp_path / "policy.json"
    policy_path.write_text(fixture_policy().model_dump_json(), encoding="utf-8")
    path = tmp_path / "raw.json"
    case = row(["떡볶이"], ["치즈떡볶이"])
    case["prediction"]["items"][0].update(count=None, unit="그릇", category_hint="MEAL",
                                        tags=[], confidence="high", packaged_product=None)
    case["prediction"]["likely_consumed_all"] = None
    path.write_text(json.dumps({"cases": [case]}), encoding="utf-8")
    before = path.read_bytes()
    output = tmp_path / "new.json"
    result = rescore_reports([path], policy_path, output)
    assert result["api_calls"] == 0 and result["completion_claim"] is False
    assert result["accuracy_summary"]["food_name"]["rate"] == 0
    assert path.read_bytes() == before
    assert result["source_reports"][0]["sha256"]
    with pytest.raises(FileExistsError):
        rescore_reports([path], policy_path, output)
    with pytest.raises(ValueError, match="중복"):
        rescore_reports([path, path], policy_path, tmp_path / "duplicate.json")
    assert not (tmp_path / "duplicate.json").exists()


def test_no_food_truth_is_unmeasured_not_perfect():
    score = score_family_cases([])
    assert score["food_family"]["rate"] is None
    assert score["within_family_nutrition"]["status"] == "NOT_MEASURED"


def test_default_nutrient_sources_are_identical_to_existing_verified_reference():
    from food_management import load_management_policy
    existing = load_management_policy().groups[0].nutrition_reference
    for entry in load_family_policy().nutrition:
        original = next(item for item in existing.entries if item.name == entry.name)
        assert entry.basis_g == existing.basis_g
        assert entry.source_url == existing.source_url
        assert entry.carbohydrate_g == original.carbohydrate_g
        assert entry.sugars_g == original.sugars_g


def test_console_separates_catalog_coverage_and_changed_name_measurement(capsys):
    cases = [row(["김밥", "국수"], ["참치김밥", "국수"])]
    print_accuracy_summary(summarize_accuracy(cases, family_policy=fixture_policy()))
    output = capsys.readouterr().out
    assert "계열 명칭 표 커버리지: 1/2개 정답 음식" in output
    assert "동일 계열 영양 비교: 0/1개 계열 일치" in output
    assert "음식명이 다른 동일 계열 중 영양 비교 완료: 0쌍" in output
    assert "음식명 변경 쌍의 당류 참고값 평균 절대 차이: 미측정" in output
