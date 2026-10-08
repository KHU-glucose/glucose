"""Run human-labelled food photos through the production recognizer and score them."""

import argparse
import asyncio
import hashlib
import json
import statistics
import time
import unicodedata
from datetime import datetime, timezone
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, StrictBool, StrictInt
from food_prompts import ACTIVE_PROMPT_VERSION, PROMPTS, get_food_prompt


class ExpectedFood(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: str = Field(min_length=1)
    aliases: list[str] = Field(default_factory=list)
    count: StrictInt | None = Field(default=None, ge=0)
    unit: Literal["개", "조각", "팩", "컵", "그릇", "공기", "병", "잔"] | None = None
    category_hint: Literal["MEAL", "SNACK", "FAST_SUGAR", "DRINK", "ALCOHOL"] | None = None


class Label(BaseModel):
    model_config = ConfigDict(extra="forbid")
    file: str
    is_food_photo: StrictBool
    items: list[ExpectedFood]
    context: Literal["MEAL", "SNACK", "HYPO_TREATMENT", "ALCOHOL"] | None = None
    source: str | None = None
    license: str | None = None
    dataset_group: Literal["MEAL", "SNACK", "HYPO", "PACKAGED", "NONFOOD"] | None = None
    annotation_complete: StrictBool = False


def normalize_name(name: str) -> str:
    return "".join(unicodedata.normalize("NFKC", name).casefold().split())


def names_match(expected: dict, predicted: dict) -> bool:
    accepted = [expected["name"], *expected.get("aliases", [])]
    return normalize_name(predicted["name"]) in {normalize_name(name) for name in accepted}


def matching_pairs(expected: list[dict], predicted: list[dict], predicate) -> list[tuple[int, int]]:
    """Maximum one-to-one matching; one prediction cannot score multiple labels."""
    owners: dict[int, int] = {}

    def assign(index: int, visited: set[int]) -> bool:
        for candidate, prediction in enumerate(predicted):
            if candidate in visited or not predicate(expected[index], prediction):
                continue
            visited.add(candidate)
            if candidate not in owners or assign(owners[candidate], visited):
                owners[candidate] = index
                return True
        return False

    for index in range(len(expected)):
        assign(index, set())
    return sorted((owner, candidate) for candidate, owner in owners.items())


def matching_count(expected: list[dict], predicted: list[dict], predicate) -> int:
    return len(matching_pairs(expected, predicted, predicate))


def fraction(correct: int, total: int) -> dict:
    return {"correct": correct, "total": total, "rate": round(correct / total, 4) if total else None}




def score_cases(cases: list[dict]) -> dict:
    name_hits = count_hits = category_hits = expected_total = predicted_total = 0
    count_total = category_total = photo_hits = nonfood_hits = nonfood_total = 0
    successes = 0
    latencies = []
    input_tokens = output_tokens = 0
    snack_count_hits = snack_count_total = fast_hits = fast_total = 0
    fast_positive_hits = fast_positive_total = fast_negative_hits = fast_negative_total = 0
    for case in cases:
        truth = case["expected"]
        prediction = case.get("prediction")
        expected = truth["items"]
        predicted = prediction["items"] if prediction else []
        countable = [item for item in expected if item.get("count") is not None]
        categorized = [item for item in expected if item.get("category_hint") is not None]
        snack_countable = [item for item in countable if item.get("category_hint") in {"SNACK", "FAST_SUGAR"}]
        snack_count_total += len(snack_countable)
        snack_count_hits += matching_count(
            snack_countable, predicted,
            lambda a, b: names_match(a, b) and a["count"] == b.get("count") and a["unit"] == b.get("unit"),
        )
        fast_total += len(categorized)
        fast_predicate = lambda a, b: names_match(a, b) and (
            (a["category_hint"] == "FAST_SUGAR") == (b.get("category_hint") == "FAST_SUGAR")
        )
        fast_hits += matching_count(categorized, predicted, fast_predicate)
        positives = [item for item in categorized if item["category_hint"] == "FAST_SUGAR"]
        negatives = [item for item in categorized if item["category_hint"] != "FAST_SUGAR"]
        fast_positive_total += len(positives)
        fast_negative_total += len(negatives)
        fast_positive_hits += matching_count(positives, predicted, fast_predicate)
        fast_negative_hits += matching_count(negatives, predicted, fast_predicate)
        expected_total += len(expected)
        predicted_total += len(predicted)
        count_total += len(countable)
        category_total += len(categorized)
        name_hits += matching_count(expected, predicted, names_match)
        count_hits += matching_count(
            countable, predicted,
            lambda a, b: names_match(a, b) and a["count"] == b.get("count") and a["unit"] == b.get("unit"),
        )
        category_hits += matching_count(
            categorized, predicted,
            lambda a, b: names_match(a, b) and a["category_hint"] == b.get("category_hint"),
        )
        nonfood_total += not truth["is_food_photo"]
        if prediction is not None:
            successes += 1
            classification_ok = prediction["is_food_photo"] == truth["is_food_photo"]
            if not truth["is_food_photo"]:
                classification_ok = classification_ok and not predicted
                nonfood_hits += classification_ok
            photo_hits += classification_ok
            latencies.append(case["latency_ms"])
            input_tokens += case["meta"]["input_tokens"]
            output_tokens += case["meta"]["output_tokens"]
    metrics = {
        "attempted_images": len(cases),
        "successful_response_rate": fraction(successes, len(cases)),
        "food_photo_accuracy": fraction(photo_hits, len(cases)),
        "nonfood_rejection_rate": fraction(nonfood_hits, nonfood_total),
        "food_name_recall": fraction(name_hits, expected_total),
        "food_name_precision": fraction(name_hits, predicted_total),
        "food_name_f1": round(2 * name_hits / (expected_total + predicted_total), 4)
        if expected_total + predicted_total else None,
        "count_and_unit_accuracy": fraction(count_hits, count_total),
        "category_hint_accuracy": fraction(category_hits, category_total),
        "snack_count_and_unit_accuracy": fraction(snack_count_hits, snack_count_total),
        "fast_sugar_detection_accuracy": fraction(fast_hits, fast_total),
        "fast_sugar_positive_recall": fraction(fast_positive_hits, fast_positive_total),
        "fast_sugar_negative_accuracy": fraction(fast_negative_hits, fast_negative_total),
        "median_success_latency_ms": statistics.median(latencies) if latencies else None,
        "median_attempt_latency_ms": statistics.median([case["latency_ms"] for case in cases]) if cases else None,
        "reported_input_tokens": input_tokens,
        "reported_output_tokens": output_tokens,
    }
    return metrics


def assess_targets(metrics: dict) -> dict:
    """Per-run observations, not a completion claim or pure schema-validation metric."""
    targets = {}
    for key, threshold in {
        "food_name_recall": 0.85,
        "snack_count_and_unit_accuracy": 0.80,
        "fast_sugar_detection_accuracy": 0.95,
        "nonfood_rejection_rate": 0.95,
        "successful_response_rate": 0.99,
    }.items():
        metric = metrics[key]
        rate = metric["rate"]
        targets[key] = {**metric, "target": threshold, "status":
                        "NOT_MEASURED" if rate is None else "PASS" if metric["correct"] / metric["total"] >= threshold else "FAIL"}
    if not metrics["fast_sugar_positive_recall"]["total"] or not metrics["fast_sugar_negative_accuracy"]["total"]:
        targets["fast_sugar_detection_accuracy"]["status"] = "INSUFFICIENT_CLASSES"
    latency = metrics["median_attempt_latency_ms"]
    targets["median_attempt_latency_ms"] = {"value": latency, "target": 3000,
        "status": "NOT_MEASURED" if latency is None else "PASS" if latency <= 3000 else "FAIL"}
    targets["schema_validation_rate"] = {"target": 0.99, "status": "NOT_MEASURED",
        "note": "SDK parse 성공은 검증된 최종 응답만 셈. 재시도 전 스키마 실패까지 계측하지 않아 순수 통과율은 별도 검증 필요."}
    targets["cost_per_image_usd"] = {"target": 0.003, "status": "NOT_MEASURED",
        "note": "토큰 사용량은 기록하지만 단가·실패·재시도 전체 청구 비용은 이 도구로 확정하지 않음."}
    return targets


def assess_dataset(cases: list[dict], split: str) -> dict:
    quotas = {"MEAL": 25, "SNACK": 15, "HYPO": 20, "PACKAGED": 10, "NONFOOD": 10}
    groups = {group: sum(case["expected"].get("dataset_group") == group for case in cases) for group in quotas}
    complete = bool(cases) and all(case["expected"].get("annotation_complete") for case in cases)
    count_labels = sum(item.get("count") is not None and item.get("category_hint") in {"SNACK", "FAST_SUGAR"}
                       for case in cases for item in case["expected"]["items"])
    ready = (60 <= len(cases) <= 100 and all(groups[group] >= quota for group, quota in quotas.items())
             and complete and count_labels >= 15 and split == "holdout")
    return {"ready": ready, "split": split, "groups": groups, "required_groups": quotas,
            "annotations_complete": complete, "count_labelled_snacks": count_labels,
            "note": "완료 판정에는 미사용 holdout과 모든 보이는 음식의 정답이 필요. 그룹을 배타적으로 채우면 최소 80장."}


def load_dataset(labels_path: Path, images_root: Path) -> list[Label]:
    from PIL import Image

    raw = json.loads(labels_path.read_text(encoding="utf-8-sig"))
    if not isinstance(raw, list) or not raw:
        raise ValueError("labels.json에는 사진별 정답을 1개 이상 배열로 넣어주세요")
    labels = [Label.model_validate(row) for row in raw]
    seen = set()
    root = images_root.resolve()
    for label in labels:
        path = (root / label.file).resolve()
        if not path.is_relative_to(root) or Path(label.file).is_absolute():
            raise ValueError("사진 경로는 images 폴더 안의 상대 경로여야 합니다")
        if path in seen:
            raise ValueError(f"중복된 사진 경로: {label.file}")
        seen.add(path)
        if label.is_food_photo != bool(label.items):
            raise ValueError(f"{label.file}: 음식이면 items를 채우고, 비음식이면 빈 배열을 쓰세요")
        if any(item.count is not None and item.unit is None for item in label.items):
            raise ValueError(f"{label.file}: 개수를 채점할 음식에는 unit이 필요합니다")
        if not path.is_file():
            raise ValueError(f"평가 사진이 없습니다: {label.file}")
        if path.suffix.lower() not in {".jpg", ".jpeg", ".png"} or path.stat().st_size > 8 * 1024 * 1024:
            raise ValueError(f"{label.file}: JPEG/PNG, 최대 8MB만 허용합니다")
        with Image.open(path) as image:
            if image.format not in {"JPEG", "PNG"}:
                raise ValueError(f"{label.file}: JPEG/PNG 이미지가 아닙니다")
            image.verify()
    return labels


async def run_evaluation(labels: list[Label], images_root: Path, report: dict, output: Path, model: str | None,
                         prompt_version: str = ACTIVE_PROMPT_VERSION):
    from fastapi import UploadFile
    from starlette.datastructures import Headers

    from food_recognizer import IMAGE_DETAIL, MAX_OUTPUT_TOKENS, FoodRecognizerFailure, OpenAIFoodRecognizer
    from main import MAX_IMAGE_EDGE, ServiceError, validate_and_prepare_image
    from models import FoodRecognitionPayload

    recognizer = OpenAIFoodRecognizer(model=model, prompt_version=prompt_version)
    if not recognizer.api_key:
        raise ValueError("OPENAI_API_KEY가 필요합니다. 저장소 루트 .env를 확인하세요")
    output.parent.mkdir(parents=True, exist_ok=True)
    # Refuse accidental overwrite of a previous evaluation.
    with output.open("x", encoding="utf-8"):
        pass
    report["model"] = recognizer.model
    report["prompt_version"] = prompt_version
    report["prompt_text"] = get_food_prompt(prompt_version)
    report["prompt_sha256"] = hashlib.sha256(report["prompt_text"].encode()).hexdigest()
    report["settings"] = {"image_detail": IMAGE_DETAIL, "max_image_edge": MAX_IMAGE_EDGE,
                          "max_output_tokens": MAX_OUTPUT_TOKENS, "context_sent": "optional label.context only"}
    report["schema_sha256"] = hashlib.sha256(json.dumps(FoodRecognitionPayload.model_json_schema(), sort_keys=True).encode()).hexdigest()
    report["cases"] = []

    def save_report():
        report["metrics"] = score_cases(report["cases"])
        report["targets"] = assess_targets(report["metrics"])
        report["dataset_readiness"] = assess_dataset(report["cases"], report.get("split", "development"))
        report["completion_passed"] = (report.get("completed", False) and report["dataset_readiness"]["ready"]
            and all(target["status"] == "PASS" for target in report["targets"].values()))
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    save_report()
    try:
        for index, label in enumerate(labels, 1):
            path = images_root / label.file
            mime = "image/png" if path.suffix.lower() == ".png" else "image/jpeg"
            case = {
                "file": label.file, "expected": label.model_dump(), "prediction": None,
                "image_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            }
            started = time.perf_counter()
            try:
                with path.open("rb") as file:
                    upload = UploadFile(file=file, headers=Headers({"content-type": mime}))
                    prepared, prepared_mime = await validate_and_prepare_image(upload, f"eval-{index}")
                result = await recognizer.recognize(prepared, prepared_mime, label.context)
                case["prediction"] = result.payload.model_dump()
                case["meta"] = result.meta.model_dump()
            except (FoodRecognizerFailure, ServiceError) as error:
                case["error_code"] = error.code
            case["latency_ms"] = round((time.perf_counter() - started) * 1000)
            report["cases"].append(case)
            save_report()
            status = "OK" if case["prediction"] is not None else case["error_code"]
            print(f"{index}/{len(labels)}: {status}", flush=True)
    finally:
        if recognizer._client is not None:
            await recognizer._client.close()
    report["completed"] = True
    save_report()


def main():
    parser = argparse.ArgumentParser(description="사람이 작성한 정답으로 음식 인식 정확도를 평가합니다")
    parser.add_argument("--labels", type=Path, default=Path("eval/food/labels.json"))
    parser.add_argument("--images", type=Path, default=Path("eval/food/images"))
    parser.add_argument("--output", type=Path)
    parser.add_argument("--model", help="없으면 현재 FOOD_MODEL / OPENAI_MODEL 설정 사용")
    parser.add_argument("--prompt-version", choices=list(PROMPTS), default=ACTIVE_PROMPT_VERSION)
    parser.add_argument("--split", choices=["development", "holdout"], default="development")
    parser.add_argument("--require-targets", action="store_true", help="완료 기준 미달·미측정·데이터 부족이면 종료 코드 1")
    parser.add_argument("--limit", type=int, default=10, help="실행할 최대 사진 수 (기본 10)")
    parser.add_argument("--check", action="store_true", help="사진과 정답만 검사; AI 호출 없음")
    args = parser.parse_args()
    try:
        if args.limit < 1:
            raise ValueError("--limit은 1 이상이어야 합니다")
        labels = load_dataset(args.labels, args.images)
        print(f"정답과 이미지 검사 통과: {len(labels)}장", flush=True)
        if args.check:
            return 0
        selected = labels[:args.limit]
        now = datetime.now(timezone.utc)
        output = args.output or Path("eval/food/reports") / f"food-{now:%Y%m%dT%H%M%S%fZ}.json"
        report = {
            "created_at_utc": now.isoformat(), "dataset_size": len(labels),
            "selected_images": len(selected), "completed": False,
            "labels_sha256": hashlib.sha256(args.labels.read_bytes()).hexdigest(),
            "split": args.split,
            "note": "API 실패도 정확도 분모에 포함. 재시도 비용은 SDK 메타에 모두 포함되지 않을 수 있음.",
        }
        asyncio.run(run_evaluation(selected, args.images, report, output, args.model, args.prompt_version))
        titles = {
            "food_name_recall": "정답 음식 인식률",
            "food_name_precision": "AI 음식명 정밀도",
            "count_and_unit_accuracy": "개수·단위 일치율",
            "snack_count_and_unit_accuracy": "간식 낱개 개수·단위 일치율",
            "fast_sugar_detection_accuracy": "계약상 FAST_SUGAR 구분 일치율",
            "food_photo_accuracy": "음식 여부 판별 정확도",
            "nonfood_rejection_rate": "비음식 사진 거르기",
            "category_hint_accuracy": "음식 분류 일치율",
            "successful_response_rate": "정상 응답률",
        }
        for key, title in titles.items():
            metric = report["metrics"][key]
            if metric["rate"] is None:
                print(f"{title}: 평가 대상 없음")
            else:
                print(f"{title}: {metric['rate'] * 100:.1f}% ({metric['correct']}/{metric['total']})")
        print(f"성공 응답 시간 중앙값: {report['metrics']['median_success_latency_ms']}ms")
        print(f"평가 보고서: {output}")
        print(f"완료 기준 종합: {'통과' if report['completion_passed'] else '미달 또는 미검증'}")
        if args.require_targets:
            return 0 if report["completion_passed"] else 1
        return 0 if report["metrics"]["successful_response_rate"]["rate"] == 1 else 1
    except (ValueError, OSError) as error:
        parser.exit(2, f"평가를 시작하지 못했습니다: {error}\n")


if __name__ == "__main__":
    raise SystemExit(main())
