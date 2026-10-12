package com.glucoselog.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.glucoselog.common.ApiException;
import com.glucoselog.episode.EpisodeProperties;
import com.glucoselog.episode.GlucoseSample;
import com.glucoselog.glucose.ReadingFlag;
import com.glucoselog.photo.PhotoContext;

/** 의료인 공유용 리포트: 사실 집계, 한계 표시, 기간 검증을 DB 없이 확인한다. */
class ClinicianReportServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-10T03:00:00Z"), ZoneOffset.UTC);

    private final DailyReportService daily = mock(DailyReportService.class);
    private final GlucoseTimelineService timeline = mock(GlucoseTimelineService.class);
    private final ClinicianReportService service =
            new ClinicianReportService(daily, timeline, new EpisodeProperties(90, 4, 2, 12, 70), CLOCK);

    private static Instant at(String hhmmUtc) {
        return Instant.parse("2026-10-01T" + hhmmUtc + ":00Z");
    }

    private void stubDay(LocalDate date, DailyReportResponse.GlucoseSummary glucose, List<GlucoseSample> samples,
            List<EpisodeSummary> episodes, int insulinCount) {
        List<DailyReportResponse.InsulinEventMarker> insulin = insulinCount == 0
                ? List.of()
                : List.of(new DailyReportResponse.InsulinEventMarker(at("09:05"), new BigDecimal("3"), "처치"));
        when(daily.getDailyReport(USER, date))
                .thenReturn(new DailyReportResponse(date, glucose, episodes, insulinCount, insulin, List.of(), List.of()));
        when(timeline.findDaySummary(USER, date))
                .thenReturn(glucose == null
                        ? Optional.empty()
                        : Optional.of(new GlucoseTimelineService.DaySummary(glucose.coverageRatio(), samples)));
    }

    @Test
    void 시작일이_종료일보다_늦으면_400() {
        assertThatThrownBy(() -> service.getReport(USER, LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 1)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getCode()).isEqualTo("INVALID_PERIOD");
                });
    }

    @Test
    void 기간이_31일을_넘으면_400() {
        assertThatThrownBy(() -> service.getReport(USER, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 2)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("PERIOD_TOO_LONG"));
    }

    @Test
    void 읽힌_값_낮은_값_경계_표시_결측을_센다_평균은_읽힌_값_전체로_계산한다() {
        LocalDate day1 = LocalDate.of(2026, 10, 1);
        LocalDate day2 = LocalDate.of(2026, 10, 2);
        List<GlucoseSample> samples = List.of(
                new GlucoseSample(at("00:00"), 120, ReadingFlag.NORMAL),
                new GlucoseSample(at("08:45"), 65, ReadingFlag.BELOW_RANGE),
                new GlucoseSample(at("10:00"), 210, ReadingFlag.ABOVE_RANGE),
                new GlucoseSample(at("10:15"), null, ReadingFlag.MISSING));
        DailyReportResponse.GlucoseSummary glucose = new DailyReportResponse.GlucoseSummary(
                new BigDecimal("0.94"), new BigDecimal("131.7"), 65, 210, 3, true);
        stubDay(day1, glucose, samples, List.of(), 0);
        stubDay(day2, null, List.of(), List.of(), 0);

        ClinicianReportResponse report = service.getReport(USER, day1, day2);

        assertThat(report.lowReadingThreshold()).isEqualTo(70);
        ClinicianReportResponse.Day first = report.days().get(0);
        assertThat(first.status()).isEqualTo("SUFFICIENT");
        assertThat(first.readingsCount()).isEqualTo(3);
        assertThat(first.missingCount()).isEqualTo(1);
        assertThat(first.lowReadingCount()).isEqualTo(1);
        assertThat(first.lowReadings()).extracting(ClinicianReportResponse.LowReading::value).containsExactly(65);
        assertThat(first.aboveRangeCount()).isEqualTo(1);
        assertThat(first.belowRangeCount()).isEqualTo(1);

        ClinicianReportResponse.Day second = report.days().get(1);
        assertThat(second.status()).isEqualTo("NO_GRAPH");
        assertThat(second.average()).isNull(); // 0이 아니라 null
        assertThat(second.readingsCount()).isZero();

        ClinicianReportResponse.Totals totals = report.totals();
        assertThat(totals.periodDays()).isEqualTo(2);
        assertThat(totals.daysSufficient()).isEqualTo(1);
        assertThat(totals.daysNoGraph()).isEqualTo(1);
        assertThat(totals.readingsCount()).isEqualTo(3);
        assertThat(totals.average()).isEqualByComparingTo("131.7");
        assertThat(totals.min()).isEqualTo(65);
        assertThat(totals.max()).isEqualTo(210);
        assertThat(totals.lowReadingCount()).isEqualTo(1);
    }

    @Test
    void 읽힌_값이_하나도_없으면_평균_최저_최고는_null() {
        LocalDate date = LocalDate.of(2026, 10, 1);
        stubDay(date, null, List.of(), List.of(), 0);

        ClinicianReportResponse report = service.getReport(USER, date, date);

        assertThat(report.totals().average()).isNull();
        assertThat(report.totals().min()).isNull();
        assertThat(report.totals().max()).isNull();
        assertThat(report.totals().readingsCount()).isZero();
    }

    @Test
    void 오늘과_이후는_진행_중으로_구분한다() {
        LocalDate yesterday = LocalDate.of(2026, 10, 9);
        LocalDate today = LocalDate.of(2026, 10, 10);
        stubDay(yesterday, null, List.of(), List.of(), 0);
        stubDay(today, null, List.of(), List.of(), 0);

        ClinicianReportResponse report = service.getReport(USER, yesterday, today);

        assertThat(report.days()).extracting(ClinicianReportResponse.Day::status)
                .containsExactly("NO_GRAPH", "IN_PROGRESS");
        assertThat(report.totals().daysInProgress()).isEqualTo(1);
    }

    @Test
    void 에피소드는_입력한_분류를_보존하고_중립적인_이름으로_내려준다() {
        LocalDate date = LocalDate.of(2026, 10, 1);
        EpisodeSummary episode = new EpisodeSummary(
                at("09:00"), PhotoContext.SNACK, PhotoContext.HYPO_TREATMENT, true, at("11:00"), true, 1);
        DailyReportResponse.GlucoseSummary glucose = new DailyReportResponse.GlucoseSummary(
                new BigDecimal("0.94"), new BigDecimal("100.0"), 100, 100, 1, true);
        stubDay(date, glucose, List.of(new GlucoseSample(at("00:00"), 100, ReadingFlag.NORMAL)), List.of(episode), 1);

        ClinicianReportResponse report = service.getReport(USER, date, date);

        ClinicianReportResponse.Episode result = report.days().get(0).episodes().get(0);
        assertThat(result.enteredContext()).isEqualTo(PhotoContext.SNACK);
        assertThat(result.effectiveContext()).isEqualTo(PhotoContext.HYPO_TREATMENT);
        assertThat(result.autoReclassified()).isTrue();
        assertThat(result.aboveRangeObserved()).isTrue();
        assertThat(report.totals().hypoTreatmentEpisodesCount()).isEqualTo(1);
        assertThat(report.totals().insulinEventsCount()).isEqualTo(1);
    }

    @Test
    void 한계_문구와_면책_문구가_항상_붙고_해석_문구는_없다() {
        LocalDate date = LocalDate.of(2026, 10, 1);
        stubDay(date, null, List.of(), List.of(), 0);

        ClinicianReportResponse report = service.getReport(USER, date, date);

        assertThat(report.disclaimer()).isEqualTo("일반 정보이며 의료 조언이 아닙니다.");
        assertThat(report.notes()).anyMatch(note -> note.contains("센서 원본 데이터가 아니에요"));
        assertThat(report.notes()).anyMatch(note -> note.contains("보간하지 않았어요"));
        assertThat(String.join(" ", report.notes())).doesNotContain("의심", "권장", "위험", "정상");
    }
}
