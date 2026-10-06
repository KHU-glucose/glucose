package com.glucoselog.intake;

import java.util.List;

import jakarta.validation.constraints.NotBlank;

/** categoryHint/tags를 보내면 사용자 수정값으로 간주해 food_catalog보다 우선한다. */
public record IntakeItemRequest(
        @NotBlank String name,
        Integer count,
        String unit,
        FoodCategoryHint categoryHint,
        List<Tag> tags) {
}
