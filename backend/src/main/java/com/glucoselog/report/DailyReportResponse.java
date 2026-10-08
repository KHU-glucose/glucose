package com.glucoselog.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** glucose가 null이면 그날 분석 완료된 그래프가 없다는 뜻이다(판단 불가). */
public record DailyReportResponse(
        LocalDate date,
        GlucoseSummary glucose,
        List<EpisodeSummary> episodes,
        int insulinEventsCount,
        List<EducationCardResponse> educationCards) {

    public record GlucoseSummary(BigDecimal coverageRatio, BigDecimal average, Integer min, Integer max, int readingsCount) {
    }
}
