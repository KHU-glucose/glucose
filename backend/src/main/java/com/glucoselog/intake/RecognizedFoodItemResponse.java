package com.glucoselog.intake;

import java.util.List;

/** ml-service 응답(docs/ml-service-contract.md)의 items[] 중 백엔드가 필요한 필드만 추린 것.
 * packaged_product, meta 같은 필드는 그대로 두면 Jackson이 알려지지 않은 필드로 무시한다. */
public record RecognizedFoodItemResponse(
        String name, Integer count, String unit, FoodCategoryHint categoryHint, List<Tag> tags, String confidence,
        String foodGroup) {
}
