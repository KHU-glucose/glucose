package com.glucoselog.intake;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record IntakeItemResponse(
        UUID id,
        String name,
        Integer count,
        String unit,
        FoodCategoryHint categoryHint,
        List<Tag> tags,
        BigDecimal sugarGrams) {

    static IntakeItemResponse from(IntakeItem item) {
        return new IntakeItemResponse(
                item.getId(),
                item.getName(),
                item.getCount(),
                item.getUnit(),
                item.getCategoryHint(),
                item.getTags(),
                item.getSugarGrams());
    }
}
