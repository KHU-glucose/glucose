package com.glucoselog.report;

import java.time.Instant;

import com.glucoselog.photo.PhotoContext;

/**
 * {@code autoReclassified}/{@code reboundDetected}가 {@code null}이면 "판단 불가"다 - 확인해봤는데
 * 아니라는 뜻(false)이 아니라, 그 시점에 쓸 글루코스 데이터가 없어서 확인 자체를 못 했다는 뜻이다.
 */
public record EpisodeSummary(
        Instant startAt,
        PhotoContext originalContext,
        PhotoContext effectiveContext,
        Boolean autoReclassified,
        Instant windowEndAt,
        Boolean reboundDetected,
        int intakeCount) {
}
