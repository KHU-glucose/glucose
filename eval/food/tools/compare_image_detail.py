"""Run v3/high on the frozen 100 photos once and compare with saved v3/low.

--check is offline; --run authorizes paid API calls. Original evidence is never
overwritten. One historical pass vs one new pass is diagnostic, not causal proof.
"""
import argparse
import asyncio
from collections import defaultdict
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import statistics
import sys

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "ml-service"))
from eval_food import Label, load_dataset, names_match, run_evaluation
from food_prompts import get_food_prompt
from run_primary_benchmark import digest, write_new

DATA = ROOT / "food_testing"
SNAPSHOT = DATA / "benchmark-v3-primary-20261008-v2"
LOW = SNAPSHOT / "v3-execution-01"
HIGH = SNAPSHOT / "v3-high-execution-01"
MODEL = "gpt-6-luna"
PROMPT = "examples-v3"
PHASES = ("pilot", "development", "reserved")


def report_index(reports, detail):
    index = {}
    for report in reports:
        if not report.get("completed") or report["settings"]["image_detail"] != detail:
            raise ValueError("완료된 요청 상세도 보고서만 비교할 수 있습니다")
        for case in report["cases"]:
            key = case["image_sha256"]
            if not key or key in index or len(case["expected"]["items"]) != 1:
                raise ValueError("사진 중복 또는 대표 음식 정답 범위 오류")
            index[key] = case
    return index


def hit(case):
    return any(names_match(case["expected"]["items"][0], item)
               for item in (case.get("prediction") or {}).get("items", []))


def performance(cases, selection):
    groups = defaultdict(lambda: {"correct": 0, "total": 0})
    for case in cases:
        for group in ("overall", selection[case["file"]]["dataset"]):
            groups[group]["total"] += 1
            groups[group]["correct"] += hit(case)
    for group in groups.values():
        group["rate"] = group["correct"] / group["total"]
    successes = [case for case in cases if case.get("prediction") is not None]
    inputs = sum((case.get("meta") or {}).get("input_tokens", 0) for case in successes)
    outputs = sum((case.get("meta") or {}).get("output_tokens", 0) for case in successes)
    return {"groups": dict(groups), "attempted_images": len(cases), "successful_images": len(successes),
        "median_attempt_latency_ms": statistics.median(case["latency_ms"] for case in cases),
        "median_success_latency_ms": statistics.median(case["latency_ms"] for case in successes) if successes else None,
        "recorded_success_input_tokens": inputs, "recorded_success_output_tokens": outputs,
        "recorded_success_total_tokens": inputs + outputs,
        "success_input_tokens_per_attempted_image": inputs / len(cases),
        "success_output_tokens_per_attempted_image": outputs / len(cases),
        "standard_uncached_success_token_cost_estimate_usd": round((inputs * 0.10 + outputs * 0.50) / 1_000_000, 6)}


def compare(low_reports, high_reports, selection):
    low, high = report_index(low_reports, "low"), report_index(high_reports, "high")
    if low.keys() != high.keys():
        raise ValueError("사진 범위가 다르면 비교하지 않습니다")
    if not low:
        raise ValueError("평가 사진이 없습니다")
    baseline = low_reports[0]
    for report in [*low_reports, *high_reports]:
        for field in ("model", "prompt_version", "prompt_sha256", "schema_sha256"):
            if report[field] != baseline[field]:
                raise ValueError(f"비교 조건이 다릅니다: {field}")
        if {k: v for k, v in report["settings"].items() if k != "image_detail"} != {
                k: v for k, v in baseline["settings"].items() if k != "image_detail"}:
            raise ValueError("상세도 외 이미지/출력 설정이 달라졌습니다")
    selected = {case["file"]: case for case in selection}
    transitions = {"both_correct": [], "low_only_correct": [], "high_only_correct": [], "both_wrong": []}
    for key, first in low.items():
        second = high[key]
        if first["file"] != second["file"] or first["expected"] != second["expected"]:
            raise ValueError("정답 또는 사진 순서 대응이 변경되었습니다")
        first_hit, second_hit = hit(first), hit(second)
        category = ("both_correct" if first_hit and second_hit else "low_only_correct" if first_hit else
                    "high_only_correct" if second_hit else "both_wrong")
        transitions[category].append({"case_index": list(low).index(key) + 1, "file": first["file"],
            "expected": first["expected"]["items"][0]["name"],
            "low_names": [item["name"] for item in (first.get("prediction") or {}).get("items", [])],
            "high_names": [item["name"] for item in (second.get("prediction") or {}).get("items", [])],
            "low_error": first.get("error_code"), "high_error": second.get("error_code")})
    first = performance(list(low.values()), selected)
    second = performance(list(high.values()), selected)
    common_successes = [key for key in low if low[key].get("prediction") is not None
                        and high[key].get("prediction") is not None]
    return {"model": baseline["model"], "prompt_version": baseline["prompt_version"],
        "scope": "동일 사진·원본 대표 클래스 정답의 이름 일치. 실제 앱 전체 음식 정확도가 아님.",
        "low": first, "high": second,
        "accuracy_delta_percentage_points": round(100 * (second["groups"]["overall"]["rate"] - first["groups"]["overall"]["rate"]), 4),
        "median_attempt_latency_ratio_high_over_low": second["median_attempt_latency_ms"] / first["median_attempt_latency_ms"],
        "input_token_ratio_high_over_low": second["recorded_success_input_tokens"] / first["recorded_success_input_tokens"]
            if first["recorded_success_input_tokens"] else None,
        "output_token_ratio_high_over_low": second["recorded_success_output_tokens"] / first["recorded_success_output_tokens"]
            if first["recorded_success_output_tokens"] else None,
        "transition_counts": {key: len(value) for key, value in transitions.items()}, "transitions": transitions,
        "both_successful_images": len(common_successes),
        "both_successful_median_latency_ms": {
            "low": statistics.median(low[key]["latency_ms"] for key in common_successes) if common_successes else None,
            "high": statistics.median(high[key]["latency_ms"] for key in common_successes) if common_successes else None},
        "completion_claim": False,
        "limitations": ["기존 low 1회와 새 high 1회. 모델 응답 변동·서버 부하·실행 시점 차이를 통제한 반복 실험이 아님.",
            "대표 클래스 라벨이며 모든 음식 정답·개수·비음식·영양·혈당 정확도를 측정하지 않음.",
            "토큰은 성공 응답 기록 합계. 실패·재시도·캐시·지역 비용 등을 반영한 실제 청구액이 아님.",
            "지연은 이미지 준비와 모델 호출·재시도 포함, HTTP 서버/큐/앱 왕복 시간 제외."],
        "pricing_source": "https://developers.openai.com/api/docs/models/gpt-6-luna"}


def prepare():
    from food_recognizer import IMAGE_DETAIL, MAX_OUTPUT_TOKENS
    from main import MAX_IMAGE_EDGE
    from models import FoodRecognitionPayload
    if (IMAGE_DETAIL, MAX_IMAGE_EDGE, MAX_OUTPUT_TOKENS) != ("high", 768, 1200):
        raise ValueError("high/768px/1200 출력 토큰 외 조건은 허용하지 않습니다")
    old_plan = json.loads((LOW / "execution-plan.json").read_text(encoding="utf-8"))
    dev_path, reserved_path = SNAPSHOT / "labels.development.json", SNAPSHOT / "labels.reserved.json"
    paths = [dev_path, reserved_path, SNAPSHOT / "label-policy.json", SNAPSHOT / "selection.json",
             LOW / "execution-plan.json", LOW / "primary-summary.json",
             *(LOW / f"raw-{phase}.json" for phase in PHASES)]
    originals = {str(path): digest(path) for path in paths}
    if digest(dev_path) != old_plan["label_hashes"]["development"] or digest(reserved_path) != old_plan["label_hashes"]["reserved"]:
        raise ValueError("기존 정답 파일이 변경되었습니다")
    if digest(SNAPSHOT / "label-policy.json") != old_plan["label_policy_sha256"] or digest(SNAPSHOT / "selection.json") != old_plan["selection_sha256"]:
        raise ValueError("기존 정답 정책 또는 사진 선택이 변경되었습니다")
    low_reports = [json.loads((LOW / f"raw-{phase}.json").read_text(encoding="utf-8")) for phase in PHASES]
    low_cases = report_index(low_reports, "low")
    prompt_hash = hashlib.sha256(get_food_prompt(PROMPT).encode()).hexdigest()
    schema_hash = hashlib.sha256(json.dumps(FoodRecognitionPayload.model_json_schema(), sort_keys=True).encode()).hexdigest()
    for report in low_reports:
        if report["model"] != MODEL or report["prompt_version"] != PROMPT or report["prompt_sha256"] != prompt_hash or report["schema_sha256"] != schema_hash:
            raise ValueError("기존 모델·프롬프트·스키마와 일치하지 않습니다")
    dev, reserved = load_dataset(dev_path, DATA), load_dataset(reserved_path, DATA)
    selection = json.loads((SNAPSHOT / "selection.json").read_text(encoding="utf-8"))["cases"]
    if len(dev) != 50 or len(reserved) != 50 or len(low_cases) != 100 or len(selection) != 100:
        raise ValueError("고정한 100장 구성이 아닙니다")
    if {label.file for label in [*dev, *reserved]} != {case["file"] for case in selection}:
        raise ValueError("사진 선택과 정답 범위가 다릅니다")
    for selected in selection:
        if digest(DATA / selected["file"]) != selected["sha256"]:
            raise ValueError("원본 사진이 변경되었습니다")
    for label in [*dev, *reserved]:
        old = next(case for case in low_cases.values() if case["file"] == label.file)
        if Label.model_validate(old["expected"]).model_dump() != label.model_dump():
            raise ValueError("기존 보고서의 정답과 현재 라벨이 다릅니다")
    return dev, reserved, low_reports, selection, originals


async def main(run):
    dev, reserved, low_reports, selection, originals = prepare()
    print("검사 통과: 동일 100장·정답·gpt-6-luna·v3·768px·1200 출력 토큰. high만 변경.", flush=True)
    if not run:
        print("오프라인 검사 완료. API 호출 없음.")
        return
    if not os.getenv("OPENAI_API_KEY"):
        raise ValueError("OPENAI_API_KEY가 필요합니다")
    HIGH.mkdir()  # Refuse accidental repeat or overwrite, including partial runs.
    plan = {"created_at_utc": datetime.now(timezone.utc).isoformat(), "model": MODEL,
        "prompt_version": PROMPT, "image_detail": "high", "maximum_unique_images": 100,
        "phase_sizes": [5, 45, 50], "original_evidence_hashes": originals,
        "stop_condition": "첫 5장 모두 실패하면 중단. 기존 서비스 재시도·timeout 정책 유지.",
        "low_execution": str(LOW), "api_use_authorized": True}
    write_new(HIGH / "execution-plan.json", plan)
    phases = [("pilot", dev[:5]), ("development", dev[5:]), ("reserved", reserved)]
    reports = []
    for phase, labels in phases:
        for path, expected_hash in originals.items():
            if digest(Path(path)) != expected_hash:
                raise ValueError("평가 중 원본 근거 파일이 변경되었습니다")
        print(f"high {phase}: {len(labels)}장", flush=True)
        report = {"created_at_utc": datetime.now(timezone.utc).isoformat(), "phase": phase,
            "dataset_size": 100, "selected_images": len(labels), "completed": False, "split": "development",
            "labels_sha256": digest(SNAPSHOT / ("labels.reserved.json" if phase == "reserved" else "labels.development.json")),
            "note": "같은 기존 100장의 high 재실행. 이미 사용한 사진이며 새 holdout이 아님."}
        await run_evaluation(labels, DATA, report, HIGH / f"raw-{phase}.json", MODEL, PROMPT)
        reports.append(report)
        if phase == "pilot" and not any(case.get("prediction") is not None for case in report["cases"]):
            print("첫 5장 모두 실패. 나머지 95장 중단.", flush=True)
            return
    result = compare(low_reports, reports, selection)
    for path, expected_hash in originals.items():
        if digest(Path(path)) != expected_hash:
            raise ValueError("원본 근거 파일이 변경되었습니다")
    result["original_evidence_unchanged"] = True
    write_new(HIGH / "comparison-summary.json", result)
    print(json.dumps({key: result[key] for key in ("low", "high", "accuracy_delta_percentage_points", "transition_counts", "original_evidence_unchanged")},
                     ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--run", action="store_true")
    args = parser.parse_args()
    asyncio.run(main(args.run))
