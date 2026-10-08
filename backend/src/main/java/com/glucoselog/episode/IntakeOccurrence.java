package com.glucoselog.episode;

import java.time.Instant;
import java.util.UUID;

import com.glucoselog.photo.PhotoContext;

/** 에피소드 묶기에 넣는 입력 하나. intake 1건에 대응한다. */
public record IntakeOccurrence(UUID intakeId, Instant occurredAt, PhotoContext context) {
}
