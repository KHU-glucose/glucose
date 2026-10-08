"""Acquire all pinned HF Food-101 shards and export original image bytes.

No model/API evaluation is performed. Re-running verifies and reuses existing
files. Source class labels are NOT complete app evaluation annotations.
"""
from __future__ import annotations

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import time

REPO = "ethz/food101"
REVISION = "83488de741c1bd1ce27aa6a2b33e19c7bdf92ca9"
COUNTS = {"train": 75750, "validation": 25250}
DEFAULT_ROOT = Path(__file__).resolve().parents[3] / "food_testing" / "food101"
OWNER = "food101-acquisition-v1"


def atomic_json(path: Path, value: object) -> None:
    temp = path.with_suffix(path.suffix + ".tmp")
    temp.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    publish(temp, path, replace=True)


def publish(temp: Path, path: Path, *, replace: bool = False) -> None:
    """Retry transient Windows/OneDrive sharing locks, never lose the temp."""
    for attempt in range(20):
        try:
            if replace:
                temp.replace(path)
            else:
                temp.rename(path)
            return
        except PermissionError:
            if attempt == 19:
                raise
            time.sleep(0.25)


def file_hash(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(4 * 1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def save_image(path: Path, data: bytes, *, ensure_parent: bool = True) -> str:
    """Never overwrite a preexisting image, including mismatching user edits."""
    digest = hashlib.sha256(data).hexdigest()
    if ensure_parent:
        path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        if file_hash(path) != digest:
            raise ValueError(f"Existing image differs; refusing to overwrite: {path}")
    else:
        # Only publish a complete file; a partial owned temp is safe to resume.
        temp = path.with_suffix(path.suffix + ".acquisition-part")
        with temp.open("wb") as stream:
            stream.write(data)
        publish(temp, path)
    return digest


def class_names(parquet) -> list[str]:
    info = json.loads(parquet.schema_arrow.metadata[b"huggingface"])
    names = info["info"]["features"]["label"]["names"]
    if len(names) != 101 or len(set(names)) != 101:
        raise ValueError("Expected exactly 101 unique source classes")
    if not all(re.fullmatch(r"[a-z0-9_]+", name) for name in names):
        raise ValueError("Unsafe source class name")
    return names


def prepare(root: Path, offline: bool) -> dict:
    from huggingface_hub import HfApi, snapshot_download

    state_path = root / "acquisition.json"
    if state_path.exists():
        state = json.loads(state_path.read_text(encoding="utf-8"))
        if (state.get("owner"), state.get("repo"), state.get("revision")) != (OWNER, REPO, REVISION):
            raise ValueError("Output directory belongs to a different acquisition/version")
    else:
        if offline:
            raise ValueError("No acquisition state for offline export")
        # The job lock is the only permitted entry before ownership is established.
        if any(p.name != ".acquisition.lock" for p in root.iterdir()):
            raise ValueError("Nonempty unmarked output folder; refusing to reuse")
        info = HfApi(token=False).dataset_info(REPO, revision=REVISION, files_metadata=True)
        shards = []
        for entry in info.siblings:
            if not re.fullmatch(r"data/(train|validation)-\d{5}-of-\d{5}\.parquet", entry.rfilename):
                continue
            if not entry.lfs or not entry.size:
                raise ValueError("Missing source file checksum/size")
            shards.append({"file": entry.rfilename, "bytes": entry.size, "sha256": entry.lfs.sha256})
        shards.sort(key=lambda x: x["file"])
        if info.sha != REVISION or len(shards) != 11:
            raise ValueError("Unexpected dataset revision or shard count")
        if Counter(s["file"].split("/")[1].split("-")[0] for s in shards) != {"train": 8, "validation": 3}:
            raise ValueError("Unexpected source splits")
        state = {"owner": OWNER, "repo": REPO, "revision": REVISION, "shards": shards}
        atomic_json(state_path, state)
    needed = sum(s["bytes"] for s in state["shards"])
    # Conservative full source + export allowance, even on a resumed job.
    if shutil.disk_usage(root).free < needed * 2 + 1024**3:
        raise ValueError("Need at least ~12 GB free for source files and exported images")
    if not offline:
        print(f"Downloading/reusing {needed:,} source bytes at {REVISION}", flush=True)
        snapshot_download(REPO, repo_type="dataset", revision=REVISION,
                          allow_patterns=["data/*.parquet", "README.md", ".gitattributes"],
                          local_dir=root / "source", max_workers=3, token=False)
    for shard in state["shards"]:
        path = root / "source" / shard["file"]
        if not path.is_file() or path.stat().st_size != shard["bytes"] or file_hash(path) != shard["sha256"]:
            raise ValueError(f"Missing/corrupt source shard: {path}")
        print(f"Source SHA256 verified: {shard['file']}", flush=True)
    return state


def export_row(root: Path, row: dict, split: str, source_file: str,
               row_index: int, names: list[str]) -> dict:
    from PIL import Image

    label = row["label"]
    if type(label) is not int or not 0 <= label < len(names):
        raise ValueError("Invalid class ID")
    data = row["image"]["bytes"]
    if not data:
        raise ValueError("Image bytes absent; external paths are not followed")
    with Image.open(io.BytesIO(data)) as img:
        suffix = {"JPEG": ".jpg", "PNG": ".png", "WEBP": ".webp"}.get(img.format)
        if suffix is None:
            raise ValueError(f"Unsupported image format {img.format}")
        size = img.size
        img.load()
    shard_id = Path(source_file).stem
    relative = Path("images") / split / names[label] / f"{shard_id}-{row_index:05d}{suffix}"
    digest = save_image(root / relative, data, ensure_parent=False)
    return {"file": relative.as_posix(), "source_dataset": REPO,
            "source_revision": REVISION, "source_split": split,
            "source_shard": source_file, "source_row": row_index,
            "source_image_path": row["image"].get("path"),
            "source_label_id": label, "source_class": names[label],
            "sha256": digest, "width": size[0], "height": size[1],
            "bytes": len(data), "annotation_complete": False}


def export(root: Path, state: dict) -> dict:
    import pyarrow.parquet as pq

    split_counts = Counter()
    class_counts = {split: Counter() for split in COUNTS}
    seen_hashes: dict[str, str] = {}
    cross_split_duplicates = 0
    image_bytes = 0
    names = None
    manifest_tmp = root / "manifest.jsonl.tmp"
    with manifest_tmp.open("w", encoding="utf-8", newline="\n") as manifest, ThreadPoolExecutor(max_workers=6) as pool:
        for shard in state["shards"]:
            source_file = shard["file"]
            split = source_file.split("/")[1].split("-")[0]
            parquet = pq.ParquetFile(root / "source" / source_file)
            current_names = class_names(parquet)
            if names is None:
                names = current_names
                atomic_json(root / "classes.json", {str(i): name for i, name in enumerate(names)})
            elif names != current_names:
                raise ValueError("Class mapping differs between shards")
            for name in names:
                (root / "images" / split / name).mkdir(parents=True, exist_ok=True)
            row_index = 0
            for batch in parquet.iter_batches(batch_size=64, columns=["image", "label"]):
                # Bounded batches and ordered map keep manifest IDs deterministic.
                futures = [pool.submit(export_row, root, row, split, source_file, row_index + i, names)
                           for i, row in enumerate(batch.to_pylist())]
                for future in futures:
                    record = future.result()
                    digest = record["sha256"]
                    old_split = seen_hashes.setdefault(digest, split)
                    if old_split != split:
                        cross_split_duplicates += 1
                    manifest.write(json.dumps(record, ensure_ascii=False) + "\n")
                    split_counts[split] += 1
                    class_counts[split][record["source_class"]] += 1
                    image_bytes += record["bytes"]
                    row_index += 1
            manifest.flush()
            if row_index != parquet.metadata.num_rows:
                raise ValueError("Exported row count does not match shard")
            print(f"Exported {source_file}: {row_index:,}; total {sum(split_counts.values()):,}/101,000", flush=True)
            atomic_json(root / "progress.json", {"phase": "export", "counts": dict(split_counts)})
    if dict(split_counts) != COUNTS:
        raise ValueError(f"Wrong split totals: {split_counts}")
    for split, expected in (("train", 750), ("validation", 250)):
        if len(class_counts[split]) != 101 or set(class_counts[split].values()) != {expected}:
            raise ValueError(f"Unexpected class balance: {split}")
    publish(manifest_tmp, root / "manifest.jsonl", replace=True)
    result = {"status": "complete", "repo": REPO, "revision": REVISION,
              "completed_at": datetime.now(timezone.utc).isoformat(),
              "split_counts": dict(split_counts), "classes": 101,
              "class_counts": {s: dict(c) for s, c in class_counts.items()},
              "image_bytes": image_bytes, "source_bytes": sum(s["bytes"] for s in state["shards"]),
              "unique_image_sha256": len(seen_hashes),
              "cross_split_duplicate_occurrences": cross_split_duplicates,
              "manifest_sha256": file_hash(root / "manifest.jsonl"),
              "verified": "All source SHA256, every image full decode, image SHA256, split and class counts",
              "app_annotations_complete": False, "model_evaluation_performed": False}
    atomic_json(root / "download-summary.json", result)
    atomic_json(root / "progress.json", {"phase": "complete", "counts": dict(split_counts)})
    print(json.dumps({k: v for k, v in result.items() if k != "class_counts"}, ensure_ascii=False, indent=2), flush=True)
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=DEFAULT_ROOT)
    parser.add_argument("--offline", action="store_true", help="Verify/export previously downloaded shards without network")
    args = parser.parse_args()
    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=True)
    os.environ.setdefault("HF_XET_CACHE", str(root / "source" / ".cache" / "xet"))
    from filelock import FileLock
    with FileLock(root / ".acquisition.lock", timeout=0):
        atomic_json_path = root / "progress.json"
        # Ownership must be established before writing progress or other metadata.
        state = prepare(root, args.offline)
        atomic_json(atomic_json_path, {"phase": "source_verified"})
        export(root, state)


if __name__ == "__main__":
    # Keep library caches in the requested dataset directory, not a global cache.
    os.environ.setdefault("HF_HUB_DISABLE_IMPLICIT_TOKEN", "1")
    main()
