package com.glucoselog.insulin;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record InsulinEventRequest(
        @NotNull Instant occurredAt,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal units,
        @NotBlank String kind) {
}
