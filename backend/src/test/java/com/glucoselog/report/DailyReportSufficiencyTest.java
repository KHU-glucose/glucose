package com.glucoselog.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.glucoselog.episode.EpisodeProperties;
import com.glucoselog.episode.GlucoseSample;
import com.glucoselog.glucose.ReadingFlag;
import com.glucoselog.insulin.InsulinEventRepository;

/** 기록 충분성(coverage_ratio >= 설정 기준)의 경계값을 DB 없이 확인한다. */
class DailyReportSufficiencyTest {

    private static final UUID USER = UUID.randomUUID();
    private static final LocalDate DATE = LocalDate.of(2026, 10, 1);

    private final GlucoseTimelineService timeline = mock(GlucoseTimelineService.class);
    private final EpisodeReportService episodes = mock(EpisodeReportService.class);
    private final InsulinEventRepository insulin = mock(InsulinEventRepository.class);
    private final EducationCardRepository cards = mock(EducationCardRepository.class);
    private final DailyReportService service = new DailyReportService(
            timeline,
            episodes,
            insulin,
            cards,
            new ReportProperties(new BigDecimal("0.7")),
            new ReportSummaryBuilder(new EpisodeProperties(90, 4, 2, 12, 70)));

    private DailyReportResponse reportWithCoverage(String coverage) {
        List<GlucoseSample> samples = List.of(new GlucoseSample(Instant.parse("2026-09-30T15:00:00Z"), 120, ReadingFlag.NORMAL));
        when(timeline.findDaySummary(USER, DATE))
                .thenReturn(Optional.of(new GlucoseTimelineService.DaySummary(new BigDecimal(coverage), samples)));
        when(episodes.buildEpisodes(USER, DATE)).thenReturn(List.of());
        when(insulin.findByUserIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(any(), any(), any()))
                .thenReturn(List.of());
        return service.getDailyReport(USER, DATE);
    }

    @Test
    void 기준_미만은_부족() {
        assertThat(reportWithCoverage("0.69").glucose().sufficient()).isFalse();
    }

    @Test
    void 기준과_같으면_충분() {
        assertThat(reportWithCoverage("0.70").glucose().sufficient()).isTrue();
    }

    @Test
    void 기준_초과는_충분() {
        assertThat(reportWithCoverage("0.94").glucose().sufficient()).isTrue();
    }
}
