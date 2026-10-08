"""Re-score saved raw reports offline. No recognizer, network or paid API calls."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path

from eval_food import Label, print_accuracy_summary, summarize_accuracy
from food_family import DEFAULT_FAMILY_POLICY, FamilyPolicy
from models import FoodRecognitionPayload


def rescore_reports(paths: list[Path], policy_path: Path, output: Path) -> dict:
    if not paths:
        raise ValueError("원본 평가 보고서가 필요합니다")
    if output.exists():
        raise FileExistsError("새 출력 경로를 지정하세요. 기존 보고서는 덮어쓰지 않습니다")
    policy_bytes = policy_path.read_bytes()
    policy = FamilyPolicy.model_validate(json.loads(policy_bytes.decode("utf-8-sig")))
    cases, sources, seen = [], [], set()
    for path in paths:
        raw = path.read_bytes()
        report = json.loads(raw.decode("utf-8-sig"))
        if not isinstance(report.get("cases"), list) or not report["cases"]:
            raise ValueError(f"비어 있지 않은 원본 cases 배열이 필요합니다: {path.name}")
        sources.append({"path": str(path.resolve()), "sha256": hashlib.sha256(raw).hexdigest(),
                        "model": report.get("model"), "prompt_version": report.get("prompt_version"),
                        "labels_sha256": report.get("labels_sha256")})
        for case in report["cases"]:
            Label.model_validate(case["expected"])
            if case.get("prediction") is not None:
                FoodRecognitionPayload.model_validate(case["prediction"])
            key = case.get("image_sha256") or case.get("file")
            if not key or key in seen:
                raise ValueError("중복 사진 또는 사진 식별자가 없는 보고서는 합치지 않습니다")
            seen.add(key)
            cases.append(case)
    result = {"created_at_utc": datetime.now(timezone.utc).isoformat(),
        "evaluation_type": "RETROSPECTIVE_OFFLINE_DIAGNOSTIC", "api_calls": 0,
        "source_reports": sources, "policy_file_sha256": hashlib.sha256(policy_bytes).hexdigest(),
        "evaluated_images": len(cases), "accuracy_summary": summarize_accuracy(cases, family_policy=policy),
        "completion_claim": False,
        "note": "원본 정답·예측 변경 없이 추가 기준으로 진단. 기존 71%의 수정이나 프롬프트 개선 입증이 아님."}
    # Exclusive creation, even if another process created the output during scoring.
    with output.open("x", encoding="utf-8") as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2, allow_nan=False)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path, action="append", required=True,
                        help="기존 raw 평가 JSON. 다른 사진의 보고서만 반복 지정")
    parser.add_argument("--family-policy", type=Path, default=DEFAULT_FAMILY_POLICY)
    parser.add_argument("--output", type=Path, required=True, help="새 진단 보고서 경로")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    private_roots = [root / "food_testing", root / "eval/food/reports"]
    if not any(args.output.resolve().is_relative_to(path.resolve()) for path in private_roots):
        parser.error("원본 정보를 포함하므로 출력은 Git 제외된 food_testing/ 또는 eval/food/reports/ 안에 두세요")
    try:
        result = rescore_reports(args.report, args.family_policy, args.output)
    except (ValueError, OSError, KeyError, TypeError) as error:
        parser.error(str(error))
    print_accuracy_summary(result["accuracy_summary"])
    print("오프라인 진단 완료. API 호출 0회. 원본 보고서 변경 없음.")


if __name__ == "__main__":
    main()
