"""Correct known product identities without guessing unreadable packaging."""

from models import FoodItem, FoodRecognitionPayload


JAYU_TIME_NAMES = {
    "자유시간", "자유시간미니",
    "해태자유시간", "해태자유시간미니",
    "해태제과자유시간", "해태제과자유시간미니",
}

# Exact identities only: brand substrings also occur in beverages and other foods.
# Manufacturer references are recorded in ml-service/README.md.
CHOCOLATE_PRODUCT_NAMES = JAYU_TIME_NAMES | {
    "가나", "가나마일드", "가나밀크", "가나다크", "ghana",
    "크런키", "crunky", "킷캣", "킷캣오리지널", "킷캣2핑거오리지널", "kitkat",
    "스니커즈", "스니커즈미니", "스니커즈아몬드펀사이즈", "snickers",
}
CHOCOLATE_MARKERS = ("초콜릿", "초콜렛", "초코", "쇼콜라", "chocolate", "choco")
OTHER_FOOD_MARKERS = (
    "케이크", "케익", "브라우니", "머핀", "도넛", "빵", "크루아상", "크로와상",
    "아이스크림", "빙수", "우유", "밀크티", "라떼", "커피", "음료", "드링크",
    "프로틴", "단백질", "시리얼", "그래놀라",
    "cake", "brownie", "muffin", "donut", "bread", "croissant", "icecream",
    "chocolatemilk", "latte", "coffee", "drink", "protein", "cereal", "granola",
)


def _chocolate_item(item: FoodItem, label: str) -> FoodItem:
    sugar_free = any(marker in label for marker in ("무설탕", "제로", "sugar-free", "sugarfree"))
    tags = [tag for tag in item.tags if tag != "FAST_SUGAR"]
    if not sugar_free:
        tags.append("FAST_SUGAR")
    return item.model_copy(update={
        "name": "무설탕 초콜릿" if sugar_free else "초콜릿",
        "food_group": "간식",
        "category_hint": "SNACK" if sugar_free else "FAST_SUGAR",
        "tags": tags,
    })


def normalize_packaged_food(payload: FoodRecognitionPayload) -> FoodRecognitionPayload:
    if not payload.is_food_photo:
        return payload

    items = []
    for item in payload.items:
        product_name = item.packaged_product.product_name if item.packaged_product else None
        # A readable product name takes precedence over a generic model food name.
        identity = "".join((product_name or item.name).split())
        if identity not in JAYU_TIME_NAMES:
            items.append(item)
            continue

        label = "".join(f"{item.name} {product_name or ''}".lower().split())
        items.append(_chocolate_item(item, label))

    return payload.model_copy(update={"items": items})


def normalize_chocolate_snacks(payload: FoodRecognitionPayload) -> FoodRecognitionPayload:
    if not payload.is_food_photo:
        return payload

    items = []
    for item in payload.items:
        product = item.packaged_product
        product_name = product.product_name if product else None
        label = "".join(f"{item.name} {product_name or ''}".lower().split())
        # Beverage containers and distinct desserts retain their original identities.
        if (item.category_hint not in {"SNACK", "FAST_SUGAR"}
                or item.unit in {"컵", "병", "잔"}
                or (product and product.volume_ml is not None)
                or any(marker in label for marker in OTHER_FOOD_MARKERS)):
            items.append(item)
            continue

        identity = "".join((product_name or item.name).lower().split())
        for prefix in ("롯데웰푸드", "롯데", "네슬레", "nestle", "마즈", "mars"):
            if identity.startswith(prefix):
                identity = identity[len(prefix):]
                break
        if (identity in CHOCOLATE_PRODUCT_NAMES
                or any(marker in label for marker in CHOCOLATE_MARKERS)):
            items.append(_chocolate_item(item, label))
        else:
            items.append(item)

    return payload.model_copy(update={"items": items})
