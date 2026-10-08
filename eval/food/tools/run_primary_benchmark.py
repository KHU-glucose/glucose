"""Run the frozen, source-class-only v3 pilot (100 unique images maximum).

Primary-class recall only: NOT full-food precision, nutrition or count accuracy.
Uses the unmodified production recognizer/settings and freezes labels before API.
"""
import asyncio
from collections import Counter, defaultdict
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import statistics
import sys

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "ml-service"))
from eval_food import load_dataset, run_evaluation, names_match
from food_prompts import get_food_prompt

DATA = ROOT / "food_testing"
SNAPSHOT = DATA / "benchmark-v3-primary-20261008-v2"
VERSION = "examples-v3"
MODEL = "gpt-6-luna"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_new(path, value):
    with path.open("x", encoding="utf-8") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)


def summarize(reports, selection):
    index = {row["file"]: row for row in selection}
    cases = [case for report in reports for case in report["cases"]]
    if len({c["image_sha256"] for c in cases}) != len(cases):
        raise ValueError("Repeated evaluation image")
    groups = defaultdict(lambda: {"correct": 0, "total": 0, "successful": 0})
    misses = []
    strict_hits = 0
    from food_management import normalize_name
    for case in cases:
        source = index[case["file"]]
        expected = case["expected"]["items"][0]
        predicted = (case.get("prediction") or {}).get("items", [])
        hit = any(names_match(expected, item) for item in predicted)
        strict_hits += any(normalize_name(expected["name"]) == normalize_name(item["name"]) for item in predicted)
        for key in ("overall", source["dataset"], source["split"]):
            groups[key]["total"] += 1
            groups[key]["correct"] += hit
            groups[key]["successful"] += case.get("prediction") is not None
        if not hit:
            misses.append({"file": case["file"], "dataset": source["dataset"], "split": source["split"],
                           "source_class": source["source_class"], "expected": expected["name"],
                           "accepted_aliases": expected["aliases"], "predicted": [i["name"] for i in predicted],
                           "error_code": case.get("error_code")})
    for value in groups.values():
        value["rate"] = round(value["correct"] / value["total"], 4)
    inputs = sum((c.get("meta") or {}).get("input_tokens", 0) for c in cases)
    outputs = sum((c.get("meta") or {}).get("output_tokens", 0) for c in cases)
    return {"scope": "source-dataset primary-class presence; Korean name mapping and allowed aliases frozen before API",
            "limitations": ["Source class labels can be wrong or ambiguous; human full-photo annotations are NOT complete",
                            "No valid full-food precision/F1, count, nonfood rejection, nutrition or management score",
                            "Not proof of improvement: different images from prior 10-photo runs",
                            "Reserved split is unused-photo baseline, NOT a completed app acceptance test",
                            "Class coverage is purposive, not representative random sampling of app usage"],
            "model": MODEL, "prompt_version": VERSION,
            "prompt_sha256": hashlib.sha256(get_food_prompt(VERSION).encode()).hexdigest(),
            "groups": dict(groups), "strict_name_hits_without_aliases": strict_hits,
            "median_attempt_latency_ms": statistics.median(c["latency_ms"] for c in cases) if cases else None,
            "recorded_success_tokens": {"input": inputs, "output": outputs},
            "standard_uncached_success_token_cost_estimate_usd": round((inputs * 0.10 + outputs * 0.50)/1_000_000, 6),
            "pricing_source": "https://developers.openai.com/api/docs/models/gpt-6-luna",
            "cost_note": "Estimate for recorded successful usage only; not billed total. Cache discounts/writes, failed attempts/retries and regional/service tiers not accounted for",
            "misses": misses,
            "pre_run_source_label_review_flags": [{"case_index": 77, "reason": "source fried_rice appearance ambiguous; retained; needs human review"}],
            "completion_claim": False}


async def main():
    # load_dataset validates paths, images and schema without API access.
    dev_path = SNAPSHOT / "labels.development.json"
    reserved_path = SNAPSHOT / "labels.reserved.json"
    dev = load_dataset(dev_path, DATA)
    reserved = load_dataset(reserved_path, DATA)
    selection = json.loads((SNAPSHOT / "selection.json").read_text(encoding="utf-8"))["cases"]
    assert len(dev) == len(reserved) == 50 and len(selection) == 100
    expected_files = {row["file"] for row in selection}
    assert expected_files == {label.file for label in [*dev, *reserved]}
    assert len({row["sha256"] for row in selection}) == 100
    for row in selection:
        if digest(DATA / row["file"]) != row["sha256"]:
            raise ValueError(f"Photo changed since snapshot: {row['file']}")
    execution = SNAPSHOT / "v3-execution-01"
    execution.mkdir()  # Refuse to silently repeat a paid evaluation.
    plan = {"created_at_utc": datetime.now(timezone.utc).isoformat(), "model": MODEL, "prompt_version": VERSION,
            "prompt_sha256": hashlib.sha256(get_food_prompt(VERSION).encode()).hexdigest(),
            "label_hashes": {"development": digest(dev_path), "reserved": digest(reserved_path)},
            "label_policy_sha256": digest(SNAPSHOT / "label-policy.json"),
            "selection_sha256": digest(SNAPSHOT / "selection.json"),
            "maximum_unique_images": 100, "phase_sizes": [5, 45, 50],
            "stop_condition": "Stop after first 5 if zero valid responses; service retries unchanged",
            "annotation_complete": False, "not_app_acceptance_test": True}
    write_new(execution / "execution-plan.json", plan)
    phases = [("pilot", dev[:5], dev_path, "development"),
              ("development", dev[5:], dev_path, "development"),
              ("reserved", reserved, reserved_path, "holdout")]
    reports = []
    for phase, labels, path, split in phases:
        # Detect any mid-run label/policy edits before sending another phase.
        if digest(dev_path) != plan["label_hashes"]["development"] or digest(reserved_path) != plan["label_hashes"]["reserved"]:
            raise ValueError("Frozen labels changed during run")
        if digest(SNAPSHOT / "label-policy.json") != plan["label_policy_sha256"]:
            raise ValueError("Frozen label policy changed during run")
        report = {"created_at_utc": datetime.now(timezone.utc).isoformat(), "dataset_size": len(dev) + len(reserved),
                  "selected_images": len(labels), "completed": False, "phase": phase,
                  "labels_sha256": digest(path), "selected_labels_sha256": hashlib.sha256(json.dumps(
                      [label.model_dump() for label in labels], sort_keys=True, ensure_ascii=False).encode()).hexdigest(),
                  "split": split, "note": "Source-class-only diagnostic. Raw precision/F1 invalid with incomplete all-food truth; do not claim acceptance targets."}
        print(f"Phase {phase}: {len(labels)} unique photos", flush=True)
        await run_evaluation(labels, DATA, report, execution / f"raw-{phase}.json", MODEL, VERSION)
        reports.append(report)
        if phase == "pilot" and not any(case.get("prediction") is not None for case in report["cases"]):
            print("Pilot produced no valid responses; stopped before remaining 95 images", flush=True)
            break
    summary = summarize(reports, selection)
    summary["evaluated_images"] = sum(len(r["cases"]) for r in reports)
    summary["requested_images"] = 100
    write_new(execution / "primary-summary.json", summary)
    print(json.dumps({k: v for k, v in summary.items() if k not in {"misses", "limitations"}}, ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    asyncio.run(main())
