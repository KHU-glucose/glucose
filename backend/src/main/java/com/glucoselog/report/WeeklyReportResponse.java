package com.glucoselog.report;

import java.math.BigDecimal;
import java.time.LocalDate;

public record WeeklyReportResponse(
        LocalDate weekStart,
        LocalDate weekEnd,
        int daysWithData,
        int daysInsufficient,
        BigDecimal averageGlucose,
        int episodesCount,
        int reboundCount,
        int insulinEventsCount) {
}
