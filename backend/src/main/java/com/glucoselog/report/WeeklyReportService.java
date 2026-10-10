package com.glucoselog.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

/**
 * ISO 주(월~일) 단위로 일일 리포트 7개를 모아 집계한다. 매번 다시 계산한다(같은 입력·같은 "오늘"이면 같은 숫자).
 * 오늘 및 이후 날짜는 아직 끝나지 않은 날이라 충분/부족/누락 어디에도 세지 않고 pendingDays로 따로 센다.
 */
@Service
public class WeeklyReportService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final DailyReportService dailyReportService;
    private final ReportSummaryBuilder summaryBuilder;
    private final Clock clock;

    public WeeklyReportService(DailyReportService dailyReportService, ReportSummaryBuilder summaryBuilder, Clock clock) {
        this.dailyReportService = dailyReportService;
        this.summaryBuilder = summaryBuilder;
        this.clock = clock;
    }

    public WeeklyReportResponse getWeeklyReport(UUID userId, LocalDate anyDateInWeek) {
        LocalDate weekStart = anyDateInWeek.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate weekEnd = weekStart.plusDays(6);
        LocalDate today = LocalDate.now(clock.withZone(KST));

        List<DailyReportResponse> days = weekStart.datesUntil(weekEnd.plusDays(1))
                .map(date -> dailyReportService.getDailyReport(userId, date))
                .toList();

        int daysWithData = 0;
        int daysInsufficient = 0;
        int sufficientDays = 0;
        int lowCoverageDays = 0;
        int pendingDays = 0;
        for (DailyReportResponse day : days) {
            if (day.glucose() != null) {
                daysWithData++;
            }
            if (!day.date().isBefore(today)) {
                pendingDays++;
            } else if (day.glucose() == null) {
                daysInsufficient++;
            } else if (day.glucose().sufficient()) {
                sufficientDays++;
            } else {
                lowCoverageDays++;
            }
        }

        int episodesCount = days.stream().mapToInt(day -> day.episodes().size()).sum();
        int reboundCount = (int) days.stream()
                .flatMap(day -> day.episodes().stream())
                .filter(episode -> Boolean.TRUE.equals(episode.reboundDetected()))
                .count();
        int insulinEventsCount = days.stream().mapToInt(DailyReportResponse::insulinEventsCount).sum();

        WeeklyReportResponse counts = new WeeklyReportResponse(
                weekStart,
                weekEnd,
                daysWithData,
                daysInsufficient,
                weightedAverage(days),
                episodesCount,
                reboundCount,
                insulinEventsCount,
                sufficientDays,
                lowCoverageDays,
                pendingDays,
                List.of());
        return counts.withSummary(summaryBuilder.weekly(counts));
    }

    /** 일평균들의 단순 평균이 아니라 읽힌 값 개수로 가중한 평균. 읽힌 값이 하나도 없으면 null. */
    private static BigDecimal weightedAverage(List<DailyReportResponse> days) {
        BigDecimal weightedSum = BigDecimal.ZERO;
        long totalReadings = 0;
        for (DailyReportResponse day : days) {
            DailyReportResponse.GlucoseSummary glucose = day.glucose();
            if (glucose == null || glucose.average() == null || glucose.readingsCount() == 0) {
                continue;
            }
            weightedSum = weightedSum.add(glucose.average().multiply(BigDecimal.valueOf(glucose.readingsCount())));
            totalReadings += glucose.readingsCount();
        }
        return totalReadings == 0
                ? null
                : weightedSum.divide(BigDecimal.valueOf(totalReadings), 1, RoundingMode.HALF_UP);
    }
}
