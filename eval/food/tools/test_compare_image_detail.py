"""Offline comparison checks; no original photos or API calls."""
import copy
import unittest

from compare_image_detail import compare


class DetailComparisonTests(unittest.TestCase):
    def report(self, detail, names):
        return {"completed": True, "model": "test-model", "prompt_version": "test-v1",
            "prompt_sha256": "same-prompt", "schema_sha256": "same-schema",
            "settings": {"image_detail": detail, "max_image_edge": 768, "max_output_tokens": 1200},
            "cases": [{"file": f"{i}.jpg", "image_sha256": f"hash-{i}",
                "expected": {"is_food_photo": True, "items": [{"name": "김밥"}]},
                "prediction": {"is_food_photo": True, "items": [{"name": name}]} if name else None,
                "latency_ms": 1000 if name else 20000,
                "meta": {"input_tokens": 100 if detail == "low" else 200, "output_tokens": 50} if name else None}
                for i, name in enumerate(names)]}

    def selection(self):
        return [{"file": f"{i}.jpg", "dataset": "korean"} for i in range(3)]

    def test_same_photos_include_failure_and_both_improvements_and_regressions(self):
        low = self.report("low", ["김밥", "죽", None])
        high = self.report("high", ["죽", "김밥", "김밥"])
        originals = copy.deepcopy([low, high])
        result = compare([low], [high], self.selection())
        self.assertEqual(result["low"]["groups"]["overall"]["correct"], 1)
        self.assertEqual(result["high"]["groups"]["overall"]["correct"], 2)
        self.assertEqual(result["low"]["attempted_images"], 3)
        self.assertEqual(result["low"]["successful_images"], 2)
        self.assertEqual(result["transition_counts"]["high_only_correct"], 2)
        self.assertEqual(result["transition_counts"]["low_only_correct"], 1)
        self.assertEqual(result["low"]["recorded_success_total_tokens"], 300)
        self.assertEqual(result["high"]["recorded_success_total_tokens"], 750)
        self.assertEqual(result["input_token_ratio_high_over_low"], 3)
        self.assertFalse(result["completion_claim"])
        self.assertEqual([low, high], originals)

    def test_mismatched_conditions_fail_not_comparable(self):
        for field in ("model", "prompt_sha256", "schema_sha256", "prompt_version", "settings"):
            low, high = self.report("low", ["김밥"]), self.report("high", ["김밥"])
            if field == "settings":
                high[field]["max_image_edge"] = 1536
            else:
                high[field] = "changed"
            with self.subTest(field=field), self.assertRaises(ValueError):
                compare([low], [high], self.selection())

    def test_changed_truth_or_missing_photo_fail(self):
        for change in ("truth", "missing", "duplicate", "incomplete"):
            low, high = self.report("low", ["김밥", "김밥"]), self.report("high", ["김밥", "김밥"])
            if change == "truth":
                high["cases"][0]["expected"]["items"][0]["name"] = "떡볶이"
            elif change == "missing":
                high["cases"].pop()
            elif change == "duplicate":
                high["cases"].append(copy.deepcopy(high["cases"][0]))
            else:
                high["completed"] = False
            with self.subTest(change=change), self.assertRaises(ValueError):
                compare([low], [high], self.selection())

    def test_alias_policy_is_preserved(self):
        low, high = self.report("low", ["김밥"]), self.report("high", ["참치김밥"])
        for report in (low, high):
            report["cases"][0]["expected"]["items"][0]["aliases"] = ["참치김밥"]
        result = compare([low], [high], self.selection())
        self.assertEqual(result["transition_counts"]["both_correct"], 1)
        self.assertEqual(result["accuracy_delta_percentage_points"], 0)


if __name__ == "__main__":
    unittest.main()
