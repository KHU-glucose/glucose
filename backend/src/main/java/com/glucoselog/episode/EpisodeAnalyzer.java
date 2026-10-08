package com.glucoselog.episode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

import com.glucoselog.glucose.ReadingFlag;
import com.glucoselog.photo.PhotoContext;

/**
 * CLAUDE.md 분석 규칙(에피소드 묶기, 분석 창, 자동 재분류, 반동 판정)을 구현한 순수 함수 모음.
 * AI를 쓰지 않고, 외부 I/O(DB·HTTP)도 하지 않는다 - 입력을 받아 값을 계산해서 돌려줄 뿐이다.
 * 실제로 intake/glucose_reading을 조회해서 이 메서드들에 넘기는 건 EpisodeService(B7에서 추가)가 한다.
 */
@Component
public class EpisodeAnalyzer {

    private final EpisodeProperties properties;

    public EpisodeAnalyzer(EpisodeProperties properties) {
        this.properties = properties;
    }

    /**
     * 섭취 기록을 시간 간격으로 묶는다. 연속된 두 기록의 간격이 {@code gapMinutes} 이내면 같은 에피소드다
     * (간격이 정확히 경계값과 같아도 묶는다 - "~분 안"은 포함 범위). 입력 순서는 상관없다.
     */
    public List<List<IntakeOccurrence>> group(List<IntakeOccurrence> intakes) {
        List<IntakeOccurrence> sorted = intakes.stream()
                .sorted(Comparator.comparing(IntakeOccurrence::occurredAt))
                .toList();

        List<List<IntakeOccurrence>> groups = new ArrayList<>();
        List<IntakeOccurrence> current = new ArrayList<>();
        Instant lastTime = null;
        Duration gap = Duration.ofMinutes(properties.gapMinutes());

        for (IntakeOccurrence intake : sorted) {
            if (lastTime != null && Duration.between(lastTime, intake.occurredAt()).compareTo(gap) > 0) {
                groups.add(current);
                current = new ArrayList<>();
            }
            current.add(intake);
            lastTime = intake.occurredAt();
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        return groups;
    }

    /**
     * 에피소드 시작 직전 혈당이 임계값(기본 70) 미만이면 저혈당 처치로 자동 재분류한다. 이미
     * 처치로 기록돼 있으면 재분류가 아니다. 직전 혈당을 모르면(null) 원래 값을 그대로 쓴다.
     */
    public ClassificationResult classify(PhotoContext originalContext, Integer precedingGlucoseValue) {
        boolean shouldReclassify = precedingGlucoseValue != null
                && precedingGlucoseValue < properties.hypoGlucoseThreshold()
                && originalContext != PhotoContext.HYPO_TREATMENT;

        PhotoContext effective = shouldReclassify ? PhotoContext.HYPO_TREATMENT : originalContext;
        return new ClassificationResult(effective, shouldReclassify);
    }

    /** 분류된 맥락에 따른 분석 창 길이. */
    public Duration windowFor(PhotoContext effectiveContext) {
        return switch (effectiveContext) {
            case MEAL, SNACK -> Duration.ofHours(properties.mealWindowHours());
            case HYPO_TREATMENT -> Duration.ofHours(properties.hypoTreatmentWindowHours());
            case ALCOHOL -> Duration.ofHours(properties.alcoholWindowHours());
        };
    }

    /**
     * 반동 판정: 저혈당 처치 에피소드에서만 의미가 있다. 분석 창 안의 혈당 중 ABOVE_RANGE로
     * 표시된 값이 하나라도 있으면 반동으로 본다(그래프에서 읽은 사용자별 목표범위 상단을 그대로
     * 쓴다 - 별도의 숫자 임계값을 코드에 고정하지 않는다. Dave 확인).
     */
    public boolean detectRebound(PhotoContext effectiveContext, List<GlucoseSample> windowSamples) {
        if (effectiveContext != PhotoContext.HYPO_TREATMENT) {
            return false;
        }
        return windowSamples.stream().anyMatch(sample -> sample.flag() == ReadingFlag.ABOVE_RANGE);
    }

    public record ClassificationResult(PhotoContext effectiveContext, boolean autoReclassifiedAsHypoTreatment) {
    }
}
