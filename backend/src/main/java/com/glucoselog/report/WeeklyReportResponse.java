package com.glucoselog.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record WeeklyReportResponse(
        LocalDate weekStart,
        LocalDate weekEnd,
        int daysWithData,
        int daysInsufficient,
        BigDecimal averageGlucose,
        int episodesCount,
        int reboundCount,
        int insulinEventsCount,
        int sufficientDays,
        int lowCoverageDays,
        int pendingDays,
        List<String> summary) {

    WeeklyReportResponse withSummary(List<String> summary) {
        return new WeeklyReportResponse(
                weekStart, weekEnd, daysWithData, daysInsufficient, averageGlucose, episodesCount, reboundCount,
                insulinEventsCount, sufficientDays, lowCoverageDays, pendingDays, summary);
    }
}
