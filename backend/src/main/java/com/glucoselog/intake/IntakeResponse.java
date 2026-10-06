package com.glucoselog.intake;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.glucoselog.photo.PhotoContext;

public record IntakeResponse(
        UUID id,
        PhotoContext context,
        Instant occurredAt,
        UUID photoId,
        List<IntakeItemResponse> items,
        Instant createdAt,
        Instant updatedAt) {

    static IntakeResponse from(Intake intake) {
        return new IntakeResponse(
                intake.getId(),
                intake.getContext(),
                intake.getOccurredAt(),
                intake.getPhotoId(),
                intake.getItems().stream().map(IntakeItemResponse::from).toList(),
                intake.getCreatedAt(),
                intake.getUpdatedAt());
    }
}
