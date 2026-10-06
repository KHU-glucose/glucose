package com.glucoselog.intake;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "intake_item")
public class IntakeItem {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "intake_id", nullable = false)
    private Intake intake;

    @Column(nullable = false)
    private String name;

    @Column
    private Integer count;

    @Column
    private String unit;

    @Enumerated(EnumType.STRING)
    @Column(name = "category_hint")
    private FoodCategoryHint categoryHint;

    @Convert(converter = TagListConverter.class)
    @Column
    private List<Tag> tags;

    @Column(name = "sugar_grams")
    private BigDecimal sugarGrams;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "food_catalog_id")
    private FoodCatalog foodCatalog;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected IntakeItem() {
    }

    IntakeItem(
            String name,
            Integer count,
            String unit,
            FoodCategoryHint categoryHint,
            List<Tag> tags,
            BigDecimal sugarGrams,
            FoodCatalog foodCatalog,
            int sortOrder) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.count = count;
        this.unit = unit;
        this.categoryHint = categoryHint;
        this.tags = tags;
        this.sugarGrams = sugarGrams;
        this.foodCatalog = foodCatalog;
        this.sortOrder = sortOrder;
    }

    void setIntake(Intake intake) {
        this.intake = intake;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Integer getCount() {
        return count;
    }

    public String getUnit() {
        return unit;
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

    public int getSortOrder() {
        return sortOrder;
    }
}
