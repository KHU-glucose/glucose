"""Freeze a source-label-only 100-photo diagnostic, never invent full truth.

Uses 40 Korean photos, 40 Food-101 original validation photos and 20 Grocery
original test photos. No image or expected label is sent to a model here.
Human all-food/count/management annotations remain incomplete.
"""
import csv
import hashlib
import json
from pathlib import Path
from collections import Counter
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[3]
IMAGES = ROOT / "food_testing"
OUTPUT = IMAGES / "benchmark-v3-primary-20261008-v2"
SEED = "primary-v3-20261008-v1"
KOREAN = {
    "갈비구이": ["구운갈비"], "갈비탕": [], "갈치구이": [], "감자채볶음": ["감자볶음"],
    "계란찜": ["달걀찜"], "김밥": [], "김치찌개": [], "된장찌개": [], "떡볶이": [],
    "만두": ["찐만두", "군만두", "물만두", "고기만두", "김치만두"], "배추김치": ["김치"], "새우튀김": [],
    "삼계탕": [], "식혜": [], "약과": [], "자장면": ["짜장면"], "족발": [],
    "찜닭": ["안동찜닭"], "피자": ["치즈피자", "페퍼로니피자", "콤비네이션피자"],
    "후라이드치킨": ["프라이드치킨", "치킨", "닭튀김"],
}
FOOD101 = {
    "bibimbap": ("비빔밥", []), "fried_rice": ("볶음밥", ["새우볶음밥", "계란볶음밥"]),
    "ramen": ("라멘", ["일본라멘", "일본식라멘", "라면", "인스턴트라면"]),
    "pizza": ("피자", ["치즈피자", "페퍼로니피자", "콤비네이션피자"]),
    "hamburger": ("햄버거", ["버거", "치즈버거", "소고기버거"]), "hot_dog": ("핫도그", []),
    "french_fries": ("감자튀김", ["프렌치프라이"]), "donuts": ("도넛", ["도너츠", "초콜릿도넛", "초코도넛", "글레이즈드도넛"]),
    "macarons": ("마카롱", []), "cup_cakes": ("컵케이크", ["컵케익", "초콜릿컵케이크", "초코컵케이크", "바닐라컵케이크"]),
    "cheesecake": ("치즈케이크", ["치즈케익"]), "chocolate_cake": ("초콜릿케이크", ["초코케이크", "초코케익"]),
    "ice_cream": ("아이스크림", ["바닐라아이스크림", "초콜릿아이스크림", "딸기아이스크림"]),
    "pancakes": ("팬케이크", ["핫케이크"]), "waffles": ("와플", []),
    "omelette": ("오믈렛", ["오믈레트"]), "sushi": ("초밥", ["모둠초밥", "연어초밥"]),
    "sashimi": ("회", ["생선회", "모둠회", "연어회", "참치회", "광어회"]),
    "grilled_salmon": ("연어구이", ["구운연어", "연어스테이크"]),
    "spaghetti_carbonara": ("까르보나라", ["카르보나라", "까르보나라스파게티", "카르보나라스파게티", "까르보나라파스타"]),
}
GROCERY = {
    "Apple": ("사과", []), "Banana": ("바나나", []), "Orange": ("오렌지", []),
    "Kiwi": ("키위", ["골드키위", "그린키위"]), "Avocado": ("아보카도", []), "Pear": ("배", ["서양배"]),
    "Juice": ("주스", ["과일주스", "사과주스", "오렌지주스", "자몽주스", "과즙음료"]),
    "Milk": ("우유", []), "Soy-Milk": ("두유", ["콩우유"]),
    "Pepper": ("파프리카", ["피망", "빨간파프리카", "노란파프리카", "주황파프리카", "초록파프리카", "청피망"]),
}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def rank(candidate):
    return hashlib.sha256((SEED + candidate["file"]).encode()).hexdigest()


def main():
    if OUTPUT.exists():
        raise ValueError("Snapshot already exists; never overwrite frozen labels")
    excluded = set()
    for path in (ROOT / "eval/food/reports").glob("*.json"):
        for case in json.loads(path.read_text(encoding="utf-8")).get("cases", []):
            if case.get("image_sha256"):
                excluded.add(case["image_sha256"])
    for label in json.loads((ROOT / "eval/food/labels.json").read_text(encoding="utf-8")):
        excluded.add(sha(IMAGES / "2018-01-011.한국음식이미지_sample" / label["file"]))
    pools = {"korean": {}, "food101": {}, "grocery": {}}
    for name, aliases in KOREAN.items():
        paths = (IMAGES / "2018-01-011.한국음식이미지_sample" / name).iterdir()
        pools["korean"][name] = [{"file": p.relative_to(IMAGES).as_posix(), "name": name, "aliases": aliases,
                                   "source_class": name, "sha256": sha(p)}
                                  for p in paths if p.is_file() and p.suffix.lower() in {".jpg", ".jpeg", ".png"}]
    # Avoid duplicate bytes anywhere in Food-101, not only selected classes/splits.
    manifest_path = IMAGES / "food101/manifest.jsonl"
    hashes = Counter()
    with manifest_path.open(encoding="utf-8") as stream:
        for line in stream:
            hashes[json.loads(line)["sha256"]] += 1
    with manifest_path.open(encoding="utf-8") as stream:
        for line in stream:
            row = json.loads(line)
            cls = row["source_class"]
            if row["source_split"] != "validation" or cls not in FOOD101 or hashes[row["sha256"]] != 1:
                continue
            name, aliases = FOOD101[cls]
            pools["food101"].setdefault(cls, []).append({"file": "food101/" + row["file"],
                "name": name, "aliases": aliases, "source_class": cls, "sha256": row["sha256"]})
    dataset = IMAGES / "GroceryStoreDataset-master/dataset"
    with (dataset / "classes.csv").open(encoding="utf-8-sig", newline="") as stream:
        classes = {int(row["Class ID (int)"]): row for row in csv.DictReader(stream)}
    for line in (dataset / "test.txt").read_text(encoding="utf-8").splitlines():
        relative, fine, coarse = (part.strip() for part in line.split(","))
        source = classes[int(fine)]
        cls = source["Coarse Class Name (str)"]
        if cls not in GROCERY:
            continue
        if int(source["Coarse Class ID (int)"]) != int(coarse):
            raise ValueError("Grocery class index mismatch")
        name, aliases = GROCERY[cls]
        path = dataset / relative
        pools["grocery"].setdefault(cls, []).append({"file": path.relative_to(IMAGES).as_posix(),
            "name": name, "aliases": aliases, "source_class": source["Class Name (str)"],
            "coarse_class": cls, "sha256": sha(path)})
    selected = {"development": [], "reserved": []}
    seen = set(excluded)
    for source_name, groups in pools.items():
        for cls in sorted(groups):
            candidates = [c for c in sorted(groups[cls], key=rank) if c["sha256"] not in seen]
            choices = []
            for candidate in candidates:
                if candidate["sha256"] not in seen:
                    choices.append(candidate)
                    seen.add(candidate["sha256"])
                if len(choices) == 2:
                    break
            if len(choices) != 2:
                raise ValueError(f"Not enough unused distinct photos: {source_name}/{cls}")
            for split, candidate in zip(selected, choices):
                selected[split].append({**candidate, "dataset": source_name, "split": split})
    all_rows = [row for split in selected.values() for row in split]
    assert Counter(r["dataset"] for r in all_rows) == {"korean": 40, "food101": 40, "grocery": 20}
    assert len({r["sha256"] for r in all_rows}) == 100
    initial_path = IMAGES / "benchmark-v3-primary-20261008/selection.json"
    if initial_path.exists():
        initial = json.loads(initial_path.read_text(encoding="utf-8"))["cases"]
        assert [(r["file"], r["sha256"], r["split"]) for r in initial] == [(r["file"], r["sha256"], r["split"]) for r in all_rows]
    OUTPUT.mkdir()
    def save(name, value):
        with (OUTPUT / name).open("x", encoding="utf-8") as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
    save("selection.json", {"seed": SEED, "old_image_hashes_excluded": len(excluded), "cases": all_rows})
    save("label-policy.json", {"basis": "original dataset class labels; assistant-authored Korean mappings, NOT human full-photo annotations",
        "revision": "pre-API-v2; original v1 preserved; same photos; source ramen family and common valid subtypes clarified before any model output",
        "pre_run_review_flags": [{"case_index": 77, "reason": "source fried_rice label may be visually ambiguous; retained in source-label score; needs human review"}],
        "scope": "primary class presence; additional items are not graded", "korean": KOREAN, "food101": FOOD101, "grocery": GROCERY,
        "count_truth": None, "category_truth": None, "management_truth": None})
    for split, rows in selected.items():
        save(f"labels.{split}.json", [{"file": r["file"], "is_food_photo": True,
            "items": [{"name": r["name"], "aliases": r["aliases"], "count": None}],
            "annotation_complete": False, "source": f"{r['dataset']} class={r['source_class']}; source-class-only preliminary benchmark"}
            for r in rows])
    font = ImageFont.truetype("C:/Windows/Fonts/malgun.ttf", 16)
    for page in range(4):
        sheet = Image.new("RGB", (1200, 1200), "white")
        draw = ImageDraw.Draw(sheet)
        for i, row in enumerate(all_rows[page * 25:(page + 1) * 25]):
            x, y = (i % 5) * 240, (i // 5) * 240
            with Image.open(IMAGES / row["file"]) as picture:
                picture = picture.convert("RGB")
                picture.thumbnail((232, 190))
                sheet.paste(picture, (x + (240-picture.width)//2, y))
            draw.text((x + 4, y + 192), f"{page*25+i+1:03} {row['dataset']}", fill="black", font=font)
            draw.text((x + 4, y + 214), row["name"], fill="black", font=font)
        sheet.save(OUTPUT / f"review-{page+1}.jpg", quality=90)
    print(json.dumps({"snapshot": str(OUTPUT), "counts": dict(Counter(r['dataset'] for r in all_rows)),
        "split_counts": {s: len(r) for s, r in selected.items()}, "labels_sha256":
        {s: sha(OUTPUT / f'labels.{s}.json') for s in selected}}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
