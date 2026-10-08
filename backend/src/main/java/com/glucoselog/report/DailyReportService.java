package com.glucoselog.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.glucoselog.episode.GlucoseSample;
import com.glucoselog.insulin.InsulinEvent;
import com.glucoselog.insulin.InsulinEventRepository;

@Service
public class DailyReportService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final GlucoseTimelineService timelineService;
    private final EpisodeReportService episodeReportService;
    private final InsulinEventRepository insulinEventRepository;
    private final EducationCardRepository educationCardRepository;

    public DailyReportService(
            GlucoseTimelineService timelineService,
            EpisodeReportService episodeReportService,
            InsulinEventRepository insulinEventRepository,
            EducationCardRepository educationCardRepository) {
        this.timelineService = timelineService;
        this.episodeReportService = episodeReportService;
        this.insulinEventRepository = insulinEventRepository;
        this.educationCardRepository = educationCardRepository;
    }

    public DailyReportResponse getDailyReport(UUID userId, LocalDate date) {
        Optional<GlucoseTimelineService.DaySummary> daySummary = timelineService.findDaySummary(userId, date);
        DailyReportResponse.GlucoseSummary glucoseSummary = daySummary.map(DailyReportService::summarize).orElse(null);

        List<EpisodeSummary> episodes = episodeReportService.buildEpisodes(userId, date);

        Instant from = date.atStartOfDay(KST).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(KST).toInstant();
        List<DailyReportResponse.InsulinEventMarker> insulinEvents = insulinEventRepository
                .findByUserIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(userId, from, to)
                .stream()
                .sorted(Comparator.comparing(InsulinEvent::getOccurredAt))
                .map(event -> new DailyReportResponse.InsulinEventMarker(
                        event.getOccurredAt(), event.getUnits(), event.getKind()))
                .toList();

        List<EducationCardResponse> educationCards = new ArrayList<>();
        if (daySummary.isEmpty()) {
            educationCards.addAll(cardsFor(EducationCardTrigger.LOW_COVERAGE));
        }
        if (episodes.stream().anyMatch(episode -> Boolean.TRUE.equals(episode.reboundDetected()))) {
            educationCards.addAll(cardsFor(EducationCardTrigger.REBOUND));
        }

        return new DailyReportResponse(
                date, glucoseSummary, episodes, insulinEvents.size(), insulinEvents, educationCards);
    }

    private List<EducationCardResponse> cardsFor(EducationCardTrigger trigger) {
        return educationCardRepository.findByTrigger(trigger).stream().map(EducationCardResponse::from).toList();
    }

    private static DailyReportResponse.GlucoseSummary summarize(GlucoseTimelineService.DaySummary summary) {
        List<Integer> values = summary.samples().stream()
                .map(GlucoseSample::value)
                .filter(Objects::nonNull)
                .toList();

        BigDecimal average = values.isEmpty()
                ? null
                : BigDecimal.valueOf(values.stream().mapToInt(Integer::intValue).average().orElse(0))
                        .setScale(1, RoundingMode.HALF_UP);
        Integer min = values.isEmpty() ? null : Collections.min(values);
        Integer max = values.isEmpty() ? null : Collections.max(values);

        return new DailyReportResponse.GlucoseSummary(summary.coverageRatio(), average, min, max, values.size());
    }
}
