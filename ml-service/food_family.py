"""Offline name-family gate and cited reference-nutrient differences, not glucose prediction."""

from collections import defaultdict
import hashlib
import json
import statistics
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, HttpUrl, model_validator

from food_management import normalize_name


class FoodFamily(BaseModel):
    model_config = ConfigDict(extra="forbid")
    id: str = Field(min_length=1)
    names: list[str] = Field(min_length=1)
    note: str = Field(min_length=1)


class ReferenceNutrients(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)
    name: str = Field(min_length=1)
    basis_g: Literal[100]
    carbohydrate_g: float | None = Field(default=None, ge=0)
    sugars_g: float | None = Field(default=None, ge=0)
    source_url: HttpUrl
    source_title: str = Field(min_length=1)
    source_locator: str = Field(min_length=1)
    review_status: Literal["VERIFIED"]
    limitation: str = Field(min_length=1)


class FamilyPolicy(BaseModel):
    model_config = ConfigDict(extra="forbid")
    version: str = Field(min_length=1)
    scope: str = Field(min_length=1)
    families: list[FoodFamily] = Field(min_length=1)
    nutrition: list[ReferenceNutrients] = Field(default_factory=list)

    @model_validator(mode="after")
    def unique_catalog(self):
        ids, names, references = set(), set(), set()
        for family in self.families:
            if not family.id.strip() or family.id in ids:
                raise ValueError("음식 계열 ID는 비어 있거나 중복될 수 없습니다")
            ids.add(family.id)
            for name in family.names:
                key = normalize_name(name)
                if not key or key in names:
                    raise ValueError(f"빈 음식명 또는 계열 간 중복 음식명: {name}")
                names.add(key)
        for entry in self.nutrition:
            key = normalize_name(entry.name)
            if key not in names or key in references:
                raise ValueError(f"영양 자료는 등록된 음식마다 하나만 사용합니다: {entry.name}")
            references.add(key)
        return self

    def resolve(self, name: str) -> str | None:
        key = normalize_name(name)
        return next((family.id for family in self.families
                     if key in {normalize_name(value) for value in family.names}), None)

    def reference(self, name: str) -> ReferenceNutrients | None:
        key = normalize_name(name)
        return next((entry for entry in self.nutrition if normalize_name(entry.name) == key), None)


DEFAULT_FAMILY_POLICY = Path(__file__).with_name("food_family_policy.json")


def load_family_policy(path: Path = DEFAULT_FAMILY_POLICY) -> FamilyPolicy:
    return FamilyPolicy.model_validate(json.loads(path.read_text(encoding="utf-8-sig")))


def family_policy_evidence(policy: FamilyPolicy) -> dict:
    snapshot = policy.model_dump(mode="json")
    canonical = json.dumps(snapshot, sort_keys=True, ensure_ascii=False, separators=(",", ":"))
    return {"version": policy.version, "sha256": hashlib.sha256(canonical.encode()).hexdigest(),
            "snapshot": snapshot, "status": "EXPERIMENTAL_NO_TARGET"}


def ratio(numerator: int, denominator: int) -> dict:
    return {"correct": numerator, "total": denominator,
            "rate": round(numerator / denominator, 4) if denominator else None}


def compare_reference_pair(expected: str, predicted: str, policy: FamilyPolicy) -> dict:
    """Never compare cross-family/unknown names, even when their numbers are identical."""
    first_family, second_family = policy.resolve(expected), policy.resolve(predicted)
    result = {"expected_name": expected, "predicted_name": predicted,
              "expected_family": first_family, "predicted_family": second_family,
              "name_changed": normalize_name(expected) != normalize_name(predicted)}
    if first_family is None or second_family is None:
        return {**result, "status": "FAMILY_UNRESOLVED"}
    if first_family != second_family:
        return {**result, "status": "DIFFERENT_FAMILY"}
    first, second = policy.reference(expected), policy.reference(predicted)
    if first is None or second is None:
        return {**result, "status": "MISSING_NUTRITION_REFERENCE",
                "missing_reference_names": [name for name, ref in [(expected, first), (predicted, second)]
                                            if ref is None]}
    missing = [field for field in ("carbohydrate_g", "sugars_g")
               if getattr(first, field) is None or getattr(second, field) is None]
    if missing:
        return {**result, "status": "MISSING_NUTRIENT_VALUE", "missing_fields": missing}
    differences = {}
    for field in ("carbohydrate_g", "sugars_g"):
        delta = round(getattr(second, field) - getattr(first, field), 6)
        differences[field] = {"signed_difference_g": delta, "absolute_difference_g": abs(delta)}
    return {**result, "status": "COMPARED", "basis_g": 100, "differences": differences,
            "expected_reference": first.model_dump(mode="json"),
            "predicted_reference": second.model_dump(mode="json")}


def score_family_cases(cases: list[dict], policy: FamilyPolicy | None = None) -> dict:
    policy = policy or load_family_policy()
    hits = total = all_expected = compared = ambiguous = missing_reference = missing_value = 0
    details, compared_pairs = [], []
    for case in cases:
        truth = case["expected"]
        expected = truth["items"]
        response = case.get("prediction")
        predicted = response["items"] if response and response["is_food_photo"] else []
        all_expected += len(expected)
        expected_by_family, predicted_by_family = defaultdict(list), defaultdict(list)
        case_details = {"file": case.get("file"), "pairs": [], "unmatched_expected": [],
                        "unregistered_expected": [], "unmatched_predictions": []}
        for index, item in enumerate(expected):
            family = policy.resolve(item["name"])
            if family is None:
                case_details["unregistered_expected"].append({"index": index, "name": item["name"]})
            else:
                expected_by_family[family].append(index)
                total += 1
        for index, item in enumerate(predicted):
            family = policy.resolve(item["name"])
            if family is not None:
                predicted_by_family[family].append(index)
        used_predictions = set()
        for family, expected_indices in expected_by_family.items():
            remaining_expected = list(expected_indices)
            remaining_predicted = list(predicted_by_family[family])
            pairs = []
            # Exact names first; aliases and nutrient similarity never select a pair.
            for first in expected_indices:
                second = next((i for i in remaining_predicted if normalize_name(expected[first]["name"])
                               == normalize_name(predicted[i]["name"])), None)
                if second is not None:
                    pairs.append((first, second))
                    remaining_expected.remove(first)
                    remaining_predicted.remove(second)
            family_matches = min(len(remaining_expected), len(remaining_predicted))
            hits += len(pairs) + family_matches
            if len(remaining_expected) == len(remaining_predicted) == 1:
                pairs.append((remaining_expected.pop(), remaining_predicted.pop()))
            elif family_matches:
                # Broad-family recall is knowable, but nutrient correspondence is not.
                ambiguous += family_matches
                case_details["pairs"].append({"status": "AMBIGUOUS_PAIRING", "family": family,
                    "same_family_matches": family_matches,
                    "expected_indices": remaining_expected, "predicted_indices": remaining_predicted})
                used_predictions.update(remaining_predicted)
            for first, second in pairs:
                used_predictions.add(second)
                result = compare_reference_pair(expected[first]["name"], predicted[second]["name"], policy)
                result.update(expected_index=first, predicted_index=second)
                case_details["pairs"].append(result)
                if result["status"] == "COMPARED":
                    compared += 1
                    compared_pairs.append(result)
                elif result["status"] == "MISSING_NUTRIENT_VALUE":
                    missing_value += 1
                else:
                    missing_reference += 1
            if not family_matches:
                for first in remaining_expected:
                    case_details["unmatched_expected"].append({"index": first, "name": expected[first]["name"],
                        "family": family, "status": "NO_SAME_FAMILY_PREDICTION"})
            elif len(remaining_expected) != len(remaining_predicted):
                case_details["unmatched_expected"].append({"family": family,
                    "unmatched_count": max(0, len(remaining_expected) - len(remaining_predicted)),
                    "status": "UNBALANCED_AMBIGUOUS_FAMILY"})
        case_details["unmatched_predictions"] = [{"index": i, "name": item["name"],
            "family": policy.resolve(item["name"])} for i, item in enumerate(predicted) if i not in used_predictions]
        details.append(case_details)
    def errors(pairs):
        result = {}
        for field in ("carbohydrate_g", "sugars_g"):
            values = [pair["differences"][field] for pair in pairs]
            result[field] = {
                "mean_absolute_difference_g_per_100g": round(statistics.mean(v["absolute_difference_g"] for v in values), 6)
                    if values else None,
                "mean_signed_difference_g_per_100g": round(statistics.mean(v["signed_difference_g"] for v in values), 6)
                    if values else None}
        return result
    name_changed_pairs = [pair for pair in compared_pairs if pair["name_changed"]]
    return {
        "version": "family-gated-nutrition-v1", "policy": family_policy_evidence(policy),
        "food_family": {**ratio(hits, total), "label": "큰 분류 정확도 (등록 계열 정답 식별률)",
            "status": "MEASURED_CATALOG_SUBSET" if total else "NOT_MEASURED",
            "label_coverage": ratio(total, all_expected), "unregistered_expected_items": all_expected - total,
            "scope": "정답 음식명을 고정된 명칭 표로 연결. 부분 문자열·모델 판단·영양값으로 분류하지 않음."},
        "within_family_nutrition": {
            "status": "MEASURED_REFERENCE_SUBSET" if compared else "NOT_MEASURED",
            "basis_g": 100, "same_family_matches": hits, "compared_pairs": compared,
            "comparison_coverage": ratio(compared, hits),
            "coverage_of_catalog_expected": ratio(compared, total),
            "coverage_of_all_expected": ratio(compared, all_expected),
            "missing_reference_pairs": missing_reference, "missing_value_pairs": missing_value,
            "ambiguous_pairing_matches": ambiguous, "nutrient_errors": errors(compared_pairs),
            "name_changed_compared_pairs": len(name_changed_pairs),
            "name_changed_nutrient_errors": errors(name_changed_pairs),
            "clinical_glucose_prediction": False, "similarity_pass_threshold": None},
        "case_details": details,
        "note": "같은 계열은 영양 동등 정답이 아님. 100g당 참고값 오차이며 실제 섭취량·혈당 반응 오차가 아님. "
                "기존 음식명 점수·완료 목표는 유지. 사후 추가 지표는 진단용이고 개선 입증이 아님."}
