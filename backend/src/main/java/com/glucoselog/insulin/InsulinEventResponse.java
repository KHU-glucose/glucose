package com.glucoselog.insulin;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record InsulinEventResponse(
        UUID id, Instant occurredAt, BigDecimal units, String kind, Instant createdAt, Instant updatedAt) {

    static InsulinEventResponse from(InsulinEvent event) {
        return new InsulinEventResponse(
                event.getId(),
                event.getOccurredAt(),
                event.getUnits(),
                event.getKind(),
                event.getCreatedAt(),
                event.getUpdatedAt());
    }
}
