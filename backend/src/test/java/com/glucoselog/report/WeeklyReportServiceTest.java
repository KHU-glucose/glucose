package com.glucoselog.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.glucoselog.episode.EpisodeProperties;

/** 주간 집계(충분/부족/누락/집계 중 구분, 가중 평균)를 DB 없이 확인한다. */
class WeeklyReportServiceTest {

    private static final UUID USER = UUID.randomUUID();
    // 2026-10-05(월)~10-11(일) 주. 오늘은 수요일(10-07) 낮으로 고정.
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-07T03:00:00Z"), ZoneOffset.UTC);

    private final DailyReportService daily = mock(DailyReportService.class);
    private final WeeklyReportService service = new WeeklyReportService(
            daily, new ReportSummaryBuilder(new EpisodeProperties(90, 4, 2, 12, 70)), CLOCK);

    private static DailyReportResponse.GlucoseSummary glucose(String coverage, String average, int count, boolean sufficient) {
        return new DailyReportResponse.GlucoseSummary(
                new BigDecimal(coverage), average == null ? null : new BigDecimal(average), null, null, count, sufficient);
    }

    private void stubDay(LocalDate date, DailyReportResponse.GlucoseSummary glucose) {
        when(daily.getDailyReport(eq(USER), eq(date)))
                .thenReturn(new DailyReportResponse(date, glucose, List.of(), 0, List.of(), List.of(), List.of()));
    }

    private void stubAllDaysEmpty() {
        when(daily.getDailyReport(eq(USER), any(LocalDate.class))).thenAnswer(invocation -> {
            LocalDate date = invocation.getArgument(1);
            return new DailyReportResponse(date, null, List.of(), 0, List.of(), List.of(), List.of());
        });
    }

    @Test
    void 지난_날은_충분_부족_누락으로_나뉘고_오늘과_미래는_집계_중이다() {
        stubAllDaysEmpty();
        stubDay(MONDAY, glucose("0.94", "100.0", 90, true)); // 충분
        stubDay(MONDAY.plusDays(1), glucose("0.40", "200.0", 38, false)); // 부족
        // 10-07(수, 오늘)은 그래프가 있어도 집계 중
        stubDay(MONDAY.plusDays(2), glucose("0.30", "150.0", 29, false));

        WeeklyReportResponse response = service.getWeeklyReport(USER, MONDAY.plusDays(3));

        assertThat(response.weekStart()).isEqualTo(MONDAY);
        assertThat(response.sufficientDays()).isEqualTo(1);
        assertThat(response.lowCoverageDays()).isEqualTo(1);
        assertThat(response.pendingDays()).isEqualTo(5); // 수~일
        assertThat(response.daysInsufficient()).isEqualTo(0); // 지난 날 중 그래프 없는 날 없음
        assertThat(response.daysWithData()).isEqualTo(3); // 오늘 그래프도 데이터 있는 날에는 센다
    }

    @Test
    void 미래_날짜는_누락으로_세지_않는다() {
        stubAllDaysEmpty();

        WeeklyReportResponse response = service.getWeeklyReport(USER, MONDAY);

        assertThat(response.daysInsufficient()).isEqualTo(2); // 월·화만 (오늘은 수요일)
        assertThat(response.pendingDays()).isEqualTo(5);
        assertThat(response.sufficientDays()).isZero();
        assertThat(response.lowCoverageDays()).isZero();
        assertThat(response.averageGlucose()).isNull();
    }

    @Test
    void 끝난_주는_집계_중이_없고_기존처럼_7에서_데이터_있는_날을_뺀_값이_누락이다() {
        stubAllDaysEmpty();
        LocalDate pastMonday = LocalDate.of(2026, 9, 28);
        when(daily.getDailyReport(eq(USER), eq(pastMonday)))
                .thenReturn(new DailyReportResponse(
                        pastMonday, glucose("0.5", "120.0", 48, false), List.of(), 0, List.of(), List.of(), List.of()));

        WeeklyReportResponse response = service.getWeeklyReport(USER, pastMonday);

        assertThat(response.pendingDays()).isZero();
        assertThat(response.daysWithData()).isEqualTo(1);
        assertThat(response.daysInsufficient()).isEqualTo(6);
        assertThat(response.lowCoverageDays()).isEqualTo(1);
    }

    @Test
    void 평균은_일평균의_단순_평균이_아니라_읽힌_값_개수로_가중한다() {
        stubAllDaysEmpty();
        // 90개 평균 100, 10개 평균 200 -> 가중 110.0 (단순 평균이면 150.0)
        stubDay(MONDAY, glucose("0.94", "100.0", 90, true));
        stubDay(MONDAY.plusDays(1), glucose("0.10", "200.0", 10, false));

        WeeklyReportResponse response = service.getWeeklyReport(USER, MONDAY);

        assertThat(response.averageGlucose()).isEqualByComparingTo("110.0");
    }
}
