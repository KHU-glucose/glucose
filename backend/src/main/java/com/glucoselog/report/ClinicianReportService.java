package com.glucoselog.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.glucoselog.common.ApiException;
import com.glucoselog.episode.EpisodeProperties;
import com.glucoselog.episode.GlucoseSample;
import com.glucoselog.glucose.ReadingFlag;
import com.glucoselog.photo.PhotoContext;

/**
 * 의료인 공유용 리포트를 만든다. 일일 리포트와 같은 계산(DailyReportService)을 재사용하고, 값의 개수·경계 표시를 더한다.
 * 해석은 하지 않는다 - 낮은 값 기준은 기존 episode.hypo-glucose-threshold를 그대로 쓰고, 높은 쪽은 새 숫자 기준 없이
 * 그래프 상단 플래그(ABOVE_RANGE)의 개수만 센다. 문구는 팀 검토 전 초안이며 의료인 검토를 받은 적 없다.
 */
@Service
public class ClinicianReportService {

    static final int MAX_PERIOD_DAYS = 31;
    static final String DISCLAIMER = "일반 정보이며 의료 조언이 아닙니다.";
    static final List<String> NOTES = List.of(
            "사용자가 올린 혈당 그래프 이미지에서 읽은 추정값과 사용자가 직접 입력한 기록을 정리한 자료예요. 센서 원본 데이터가 아니에요.",
            "이미지에서 읽지 못한 구간은 비워 두었고 보간하지 않았어요. 읽힌 값만으로 계산했어요.",
            "그래프 상·하단에 붙은 값은 실제 값이 아니라 경계 표시예요.",
            "섭취·인슐린 기록 시각은 사용자가 입력했거나 사진에서 가져온 시각이라 실제 시각과 다를 수 있어요.",
            "이 자료는 기록을 정리한 것이며 진단·치료 판단을 위한 것이 아니에요. 해석은 의료인이 해요.");

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final DailyReportService dailyReportService;
    private final GlucoseTimelineService timelineService;
    private final EpisodeProperties episodeProperties;
    private final Clock clock;

    public ClinicianReportService(
            DailyReportService dailyReportService,
            GlucoseTimelineService timelineService,
            EpisodeProperties episodeProperties,
            Clock clock) {
        this.dailyReportService = dailyReportService;
        this.timelineService = timelineService;
        this.episodeProperties = episodeProperties;
        this.clock = clock;
    }

    public ClinicianReportResponse getReport(UUID userId, LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PERIOD", "시작일이 종료일보다 늦어요");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_PERIOD_DAYS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PERIOD_TOO_LONG", "기간은 최대 " + MAX_PERIOD_DAYS + "일까지예요");
        }

        LocalDate today = LocalDate.now(clock.withZone(KST));
        int threshold = episodeProperties.hypoGlucoseThreshold();

        List<ClinicianReportResponse.Day> days = new ArrayList<>();
        List<Integer> allValues = new ArrayList<>();
        int daysSufficient = 0;
        int daysLowCoverage = 0;
        int daysNoGraph = 0;
        int daysInProgress = 0;
        int lowTotal = 0;
        int aboveTotal = 0;
        int belowTotal = 0;
        int episodesTotal = 0;
        int hypoEpisodesTotal = 0;
        int insulinTotal = 0;

        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            DailyReportResponse daily = dailyReportService.getDailyReport(userId, date);
            List<GlucoseSample> samples = timelineService
                    .findDaySummary(userId, date)
                    .map(GlucoseTimelineService.DaySummary::samples)
                    .orElse(List.of());

            List<Integer> values = samples.stream()
                    .map(GlucoseSample::value)
                    .filter(Objects::nonNull)
                    .toList();
            List<ClinicianReportResponse.LowReading> lowReadings = samples.stream()
                    .filter(sample -> sample.value() != null && sample.value() < threshold)
                    .map(sample -> new ClinicianReportResponse.LowReading(sample.time(), sample.value(), sample.flag()))
                    .toList();
            int above = countFlag(samples, ReadingFlag.ABOVE_RANGE);
            int below = countFlag(samples, ReadingFlag.BELOW_RANGE);
            int missing = samples.size() - values.size();

            String status;
            if (!date.isBefore(today)) {
                status = "IN_PROGRESS";
                daysInProgress++;
            } else if (daily.glucose() == null) {
                status = "NO_GRAPH";
                daysNoGraph++;
            } else if (daily.glucose().sufficient()) {
                status = "SUFFICIENT";
                daysSufficient++;
            } else {
                status = "LOW_COVERAGE";
                daysLowCoverage++;
            }

            List<ClinicianReportResponse.Episode> episodes = daily.episodes().stream()
                    .map(ClinicianReportService::toEpisode)
                    .toList();

            DailyReportResponse.GlucoseSummary glucose = daily.glucose();
            days.add(new ClinicianReportResponse.Day(
                    date,
                    status,
                    glucose == null ? null : glucose.coverageRatio(),
                    values.size(),
                    missing,
                    glucose == null ? null : glucose.average(),
                    glucose == null ? null : glucose.min(),
                    glucose == null ? null : glucose.max(),
                    lowReadings.size(),
                    above,
                    below,
                    lowReadings,
                    episodes,
                    daily.insulinEvents()));

            allValues.addAll(values);
            lowTotal += lowReadings.size();
            aboveTotal += above;
            belowTotal += below;
            episodesTotal += episodes.size();
            hypoEpisodesTotal += (int) episodes.stream()
                    .filter(episode -> episode.effectiveContext() == PhotoContext.HYPO_TREATMENT)
                    .count();
            insulinTotal += daily.insulinEventsCount();
        }

        Optional<Integer> min = allValues.stream().min(Integer::compare);
        Optional<Integer> max = allValues.stream().max(Integer::compare);
        BigDecimal average = allValues.isEmpty()
                ? null
                : BigDecimal.valueOf(allValues.stream().mapToLong(Integer::longValue).sum())
                        .divide(BigDecimal.valueOf(allValues.size()), 1, RoundingMode.HALF_UP);

        ClinicianReportResponse.Totals totals = new ClinicianReportResponse.Totals(
                days.size(),
                daysSufficient,
                daysLowCoverage,
                daysNoGraph,
                daysInProgress,
                allValues.size(),
                average,
                min.orElse(null),
                max.orElse(null),
                lowTotal,
                aboveTotal,
                belowTotal,
                episodesTotal,
                hypoEpisodesTotal,
                insulinTotal);

        return new ClinicianReportResponse(
                from,
                to,
                clock.instant(),
                threshold,
                totals,
                Collections.unmodifiableList(days),
                NOTES,
                DISCLAIMER);
    }

    private static int countFlag(List<GlucoseSample> samples, ReadingFlag flag) {
        return (int) samples.stream().filter(sample -> sample.flag() == flag).count();
    }

    private static ClinicianReportResponse.Episode toEpisode(EpisodeSummary episode) {
        return new ClinicianReportResponse.Episode(
                episode.startAt(),
                episode.originalContext(),
                episode.effectiveContext(),
                episode.autoReclassified(),
                episode.windowEndAt(),
                episode.reboundDetected(),
                episode.intakeCount());
    }
}
