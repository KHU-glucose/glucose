"""Small offline tests; no network, production data or OpenAI calls."""
import json
import io
from pathlib import Path
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from download_food101 import class_names, save_image, atomic_json, DEFAULT_ROOT, publish, export_row


class DownloadFood101Tests(unittest.TestCase):
    def test_default_location(self):
        self.assertEqual(DEFAULT_ROOT.parts[-2:], ("food_testing", "food101"))

    def test_original_bytes_and_resume(self):
        with tempfile.TemporaryDirectory(prefix="food101-test-") as folder:
            path = Path(folder) / "images" / "test.jpg"
            digest = save_image(path, b"original")
            self.assertEqual(path.read_bytes(), b"original")
            self.assertEqual(save_image(path, b"original"), digest)

    def test_never_overwrite_conflicting_image(self):
        with tempfile.TemporaryDirectory(prefix="food101-test-") as folder:
            path = Path(folder) / "test.jpg"
            save_image(path, b"user-image")
            with self.assertRaises(ValueError):
                save_image(path, b"different")
            self.assertEqual(path.read_bytes(), b"user-image")

    def test_resume_owned_partial(self):
        with tempfile.TemporaryDirectory(prefix="food101-test-") as folder:
            path = Path(folder) / "test.jpg"
            path.with_suffix(".jpg.acquisition-part").write_bytes(b"incomplete")
            save_image(path, b"complete")
            self.assertEqual(path.read_bytes(), b"complete")

    def test_atomic_json(self):
        with tempfile.TemporaryDirectory(prefix="food101-test-") as folder:
            path = Path(folder) / "state.json"
            atomic_json(path, {"name": "음식"})
            self.assertEqual(json.loads(path.read_text(encoding="utf-8")), {"name": "음식"})

    def test_reject_unsafe_and_inconsistent_classes(self):
        names = [f"food_{i}" for i in range(101)]
        def parquet(values):
            meta = {"info": {"features": {"label": {"names": values}}}}
            return SimpleNamespace(schema_arrow=SimpleNamespace(metadata={b"huggingface": json.dumps(meta).encode()}))
        self.assertEqual(class_names(parquet(names)), names)
        for invalid in (names[:100], names[:100] + ["food_0"], names[:100] + ["../outside"]):
            with self.assertRaises(ValueError):
                class_names(parquet(invalid))

    def test_retry_transient_windows_lock(self):
        with patch.object(Path, "replace", side_effect=[PermissionError(), PermissionError(), None]) as replace:
            with patch("download_food101.time.sleep"):
                publish(Path("temp"), Path("destination"), replace=True)
        self.assertEqual(replace.call_count, 3)

    def test_permanent_lock_is_reported(self):
        with patch.object(Path, "replace", side_effect=PermissionError()) as replace:
            with patch("download_food101.time.sleep"):
                with self.assertRaises(PermissionError):
                    publish(Path("temp"), Path("destination"), replace=True)
        self.assertEqual(replace.call_count, 20)

    def test_real_jpeg_export_keeps_source_label_not_complete_annotation(self):
        from PIL import Image
        buffer = io.BytesIO()
        Image.new("RGB", (4, 3), "red").save(buffer, format="JPEG")
        data = buffer.getvalue()
        with tempfile.TemporaryDirectory(prefix="food101-test-") as folder:
            root = Path(folder)
            (root / "images" / "train" / "apple_pie").mkdir(parents=True)
            record = export_row(root, {"image": {"bytes": data, "path": "source.jpg"}, "label": 0},
                                "train", "data/train-00000-of-00008.parquet", 7, ["apple_pie"])
            self.assertEqual((root / record["file"]).read_bytes(), data)
            self.assertEqual((record["width"], record["height"]), (4, 3))
            self.assertEqual(record["source_class"], "apple_pie")
            self.assertFalse(record["annotation_complete"])
            self.assertNotIn("count", record)

    def test_reject_corrupt_or_external_image(self):
        with tempfile.TemporaryDirectory(prefix="food101-test-") as folder:
            for data, error in ((None, ValueError), (b"not-an-image", OSError)):
                with self.assertRaises(error):
                    export_row(Path(folder), {"image": {"bytes": data, "path": "outside.jpg"}, "label": 0},
                               "train", "data/train-00000-of-00008.parquet", 0, ["apple_pie"])


if __name__ == "__main__":
    unittest.main()
