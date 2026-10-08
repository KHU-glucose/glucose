package com.glucoselog.report;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** glucose가 null이면 그날 분석 완료된 그래프가 없다는 뜻이다(판단 불가). */
public record DailyReportResponse(
        LocalDate date,
        GlucoseSummary glucose,
        List<EpisodeSummary> episodes,
        int insulinEventsCount,
        List<InsulinEventMarker> insulinEvents,
        List<EducationCardResponse> educationCards) {

    public record GlucoseSummary(BigDecimal coverageRatio, BigDecimal average, Integer min, Integer max, int readingsCount) {
    }

    /** 그래프 위에 표시할 인슐린 기록(입력된 사실만, 시각순). */
    public record InsulinEventMarker(Instant occurredAt, BigDecimal units, String kind) {
    }
}
