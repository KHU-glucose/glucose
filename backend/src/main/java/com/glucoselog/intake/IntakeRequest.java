package com.glucoselog.intake;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import com.glucoselog.photo.PhotoContext;

public record IntakeRequest(
        @NotNull PhotoContext context,
        @NotNull Instant occurredAt,
        UUID photoId,
        @NotEmpty List<@Valid IntakeItemRequest> items) {
}
