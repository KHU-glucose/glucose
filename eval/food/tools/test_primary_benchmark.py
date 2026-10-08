"""Offline summary tests: never call the API or open original photos."""
import unittest
from run_primary_benchmark import summarize


class PrimaryBenchmarkTests(unittest.TestCase):
    def selection(self):
        return [{"file": "a.jpg", "dataset": "korean", "split": "development", "source_class": "계란찜"},
                {"file": "b.jpg", "dataset": "food101", "split": "reserved", "source_class": "ramen"}]

    def case(self, filename, prediction, latency=1000):
        return {"file": filename, "image_sha256": filename, "latency_ms": latency,
                "expected": {"is_food_photo": True, "annotation_complete": False,
                             "items": [{"name": "계란찜", "aliases": ["달걀찜"]}]},
                "prediction": {"is_food_photo": True, **prediction} if prediction is not None else None,
                "meta": {"input_tokens": 100, "output_tokens": 50}}

    def test_primary_alias_match_not_false_positive_precision(self):
        case = self.case("a.jpg", {"items": [{"name": "달걀찜"}, {"name": "밥"}]})
        result = summarize([{"cases": [case]}], self.selection())
        self.assertEqual(result["groups"]["overall"]["rate"], 1)
        self.assertEqual(result["strict_name_hits_without_aliases"], 0)
        self.assertNotIn("food_name_precision", result)
        self.assertFalse(result["completion_claim"])
        self.assertEqual(result["accuracy_summary"]["food_name"]["rate"], 1)
        self.assertIsNone(result["accuracy_summary"]["food_name"]["precision"]["rate"])
        self.assertEqual(result["accuracy_summary"]["glucose_management"]["status"], "NOT_MEASURED")

    def test_api_failure_stays_in_denominator(self):
        cases = [self.case("a.jpg", {"items": [{"name": "계란찜"}]}), self.case("b.jpg", None, 20000)]
        result = summarize([{"cases": cases}], self.selection())
        self.assertEqual(result["groups"]["overall"], {"correct": 1, "total": 2, "successful": 1, "rate": 0.5})
        self.assertEqual(result["median_attempt_latency_ms"], 10500)
        self.assertEqual(len(result["misses"]), 1)
        self.assertEqual(result["accuracy_summary"]["food_name"]["total"], 2)

    def test_duplicate_image_across_reports_rejected(self):
        case = self.case("a.jpg", None)
        with self.assertRaises(ValueError):
            summarize([{"cases": [case]}, {"cases": [case]}], self.selection())


if __name__ == "__main__":
    unittest.main()
