package com.glucoselog.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

/** ISO 주(월~일) 단위로 일일 리포트 7개를 모아 평균낸다. 매번 다시 계산한다(같은 입력이면 같은 숫자). */
@Service
public class WeeklyReportService {

    private final DailyReportService dailyReportService;

    public WeeklyReportService(DailyReportService dailyReportService) {
        this.dailyReportService = dailyReportService;
    }

    public WeeklyReportResponse getWeeklyReport(UUID userId, LocalDate anyDateInWeek) {
        LocalDate weekStart = anyDateInWeek.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate weekEnd = weekStart.plusDays(6);

        List<DailyReportResponse> days = weekStart.datesUntil(weekEnd.plusDays(1))
                .map(date -> dailyReportService.getDailyReport(userId, date))
                .toList();

        int daysWithData = (int) days.stream().filter(day -> day.glucose() != null).count();
        int episodesCount = days.stream().mapToInt(day -> day.episodes().size()).sum();
        int reboundCount = (int) days.stream()
                .flatMap(day -> day.episodes().stream())
                .filter(episode -> Boolean.TRUE.equals(episode.reboundDetected()))
                .count();
        int insulinEventsCount = days.stream().mapToInt(DailyReportResponse::insulinEventsCount).sum();

        List<BigDecimal> dailyAverages = days.stream()
                .map(DailyReportResponse::glucose)
                .filter(glucose -> glucose != null && glucose.average() != null)
                .map(DailyReportResponse.GlucoseSummary::average)
                .toList();
        BigDecimal averageGlucose = dailyAverages.isEmpty()
                ? null
                : dailyAverages.stream()
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(dailyAverages.size()), 1, RoundingMode.HALF_UP);

        return new WeeklyReportResponse(
                weekStart, weekEnd, daysWithData, 7 - daysWithData, averageGlucose, episodesCount, reboundCount, insulinEventsCount);
    }
}
