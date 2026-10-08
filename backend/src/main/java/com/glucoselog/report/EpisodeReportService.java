package com.glucoselog.report;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.glucoselog.episode.EpisodeAnalyzer;
import com.glucoselog.episode.GlucoseSample;
import com.glucoselog.episode.IntakeOccurrence;
import com.glucoselog.intake.Intake;
import com.glucoselog.intake.IntakeRepository;
import com.glucoselog.photo.PhotoContext;

/** 하루치 intake를 가져와 에피소드로 묶고, 글루코스 데이터를 찾아 EpisodeAnalyzer에 넘긴다. */
@Service
public class EpisodeReportService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final IntakeRepository intakeRepository;
    private final GlucoseTimelineService timelineService;
    private final EpisodeAnalyzer analyzer;

    public EpisodeReportService(
            IntakeRepository intakeRepository, GlucoseTimelineService timelineService, EpisodeAnalyzer analyzer) {
        this.intakeRepository = intakeRepository;
        this.timelineService = timelineService;
        this.analyzer = analyzer;
    }

    public List<EpisodeSummary> buildEpisodes(UUID userId, LocalDate date) {
        Instant from = date.atStartOfDay(KST).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(KST).toInstant();
        List<Intake> intakes =
                intakeRepository.findByUserIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(userId, from, to);
        if (intakes.isEmpty()) {
            return List.of();
        }

        List<IntakeOccurrence> occurrences = intakes.stream()
                .map(intake -> new IntakeOccurrence(intake.getId(), intake.getOccurredAt(), intake.getContext()))
                .toList();
        List<List<IntakeOccurrence>> groups = analyzer.group(occurrences);

        // 가장 긴 분석 창(술 12h)까지 커버하도록 전날~다다음날 범위로 글루코스를 미리 불러온다.
        List<GlucoseSample> timeline = timelineService.loadRange(userId, date.minusDays(1), date.plusDays(2));

        List<EpisodeSummary> summaries = new ArrayList<>();
        for (List<IntakeOccurrence> group : groups) {
            summaries.add(summarize(group, timeline));
        }
        return summaries;
    }

    private EpisodeSummary summarize(List<IntakeOccurrence> group, List<GlucoseSample> timeline) {
        IntakeOccurrence first = group.get(0);
        Instant startAt = first.occurredAt();
        PhotoContext originalContext = first.context();

        Integer precedingValue = precedingValue(timeline, startAt);
        EpisodeAnalyzer.ClassificationResult classification = analyzer.classify(originalContext, precedingValue);
        Boolean autoReclassified = precedingValue != null ? classification.autoReclassifiedAsHypoTreatment() : null;

        Duration window = analyzer.windowFor(classification.effectiveContext());
        Instant windowEndAt = startAt.plus(window);

        List<GlucoseSample> windowSamples = timeline.stream()
                .filter(sample -> !sample.time().isBefore(startAt) && !sample.time().isAfter(windowEndAt))
                .toList();

        Boolean reboundDetected;
        if (classification.effectiveContext() != PhotoContext.HYPO_TREATMENT) {
            reboundDetected = Boolean.FALSE;
        } else if (windowSamples.isEmpty()) {
            reboundDetected = null;
        } else {
            reboundDetected = analyzer.detectRebound(classification.effectiveContext(), windowSamples);
        }

        return new EpisodeSummary(
                startAt,
                originalContext,
                classification.effectiveContext(),
                autoReclassified,
                windowEndAt,
                reboundDetected,
                group.size());
    }

    private static Integer precedingValue(List<GlucoseSample> timeline, Instant startAt) {
        return timeline.stream()
                .filter(sample -> sample.time().isBefore(startAt) && sample.value() != null)
                .max(Comparator.comparing(GlucoseSample::time))
                .map(GlucoseSample::value)
                .orElse(null);
    }
}
