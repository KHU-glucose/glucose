package com.glucoselog.intake;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "food_catalog")
public class FoodCatalog {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "category_hint", nullable = false)
    private FoodCategoryHint categoryHint;

    @Convert(converter = TagListConverter.class)
    @Column
    private List<Tag> tags;

    @Column(name = "sugar_grams")
    private BigDecimal sugarGrams;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected FoodCatalog() {
    }

    public String getName() {
        return name;
    }

    public FoodCategoryHint getCategoryHint() {
        return categoryHint;
    }

    public List<Tag> getTags() {
        return tags;
    }

    public BigDecimal getSugarGrams() {
        return sugarGrams;
    }
}
