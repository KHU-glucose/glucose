package com.glucoselog.episode;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.glucoselog.glucose.ReadingFlag;
import com.glucoselog.photo.PhotoContext;

/** CLAUDE.md 분석 규칙의 경계값 테스트. DB·Spring 컨텍스트 없이 순수 함수만 검증한다. */
class EpisodeAnalyzerTest {

    private static final Instant BASE = Instant.parse("2026-10-01T08:00:00Z");

    private final EpisodeAnalyzer analyzer =
            new EpisodeAnalyzer(new EpisodeProperties(90, 4, 2, 12, 70));

    private static IntakeOccurrence intake(Instant time, PhotoContext context) {
        return new IntakeOccurrence(UUID.randomUUID(), time, context);
    }

    // ── group() ──────────────────────────────────────────────

    @Test
    void 간격이_경계값과_정확히_같으면_같은_에피소드로_묶인다() {
        var a = intake(BASE, PhotoContext.MEAL);
        var b = intake(BASE.plus(Duration.ofMinutes(90)), PhotoContext.MEAL);

        List<List<IntakeOccurrence>> groups = analyzer.group(List.of(a, b));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0)).containsExactly(a, b);
    }

    @Test
    void 간격이_경계값보다_1분만_커도_다른_에피소드로_나뉜다() {
        var a = intake(BASE, PhotoContext.MEAL);
        var b = intake(BASE.plus(Duration.ofMinutes(91)), PhotoContext.MEAL);

        List<List<IntakeOccurrence>> groups = analyzer.group(List.of(a, b));

        assertThat(groups).hasSize(2);
    }

    @Test
    void 입력_순서가_뒤섞여도_시간순으로_정렬해서_묶는다() {
        var a = intake(BASE, PhotoContext.MEAL);
        var b = intake(BASE.plus(Duration.ofMinutes(30)), PhotoContext.MEAL);

        List<List<IntakeOccurrence>> groups = analyzer.group(List.of(b, a));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0)).containsExactly(a, b);
    }

    @Test
    void 연쇄적으로_이어지면_전체_간격이_임계값을_넘어도_하나로_묶인다() {
        var a = intake(BASE, PhotoContext.MEAL);
        var b = intake(BASE.plus(Duration.ofMinutes(50)), PhotoContext.MEAL);
        var c = intake(BASE.plus(Duration.ofMinutes(100)), PhotoContext.MEAL); // a-c는 100분(임계값 초과)이지만 b를 거쳐 연결됨

        List<List<IntakeOccurrence>> groups = analyzer.group(List.of(a, b, c));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0)).containsExactly(a, b, c);
    }

    @Test
    void 중간에_끊기면_양쪽으로_나뉜다() {
        var a = intake(BASE, PhotoContext.MEAL);
        var b = intake(BASE.plus(Duration.ofMinutes(91)), PhotoContext.SNACK);
        var c = intake(BASE.plus(Duration.ofMinutes(91 + 50)), PhotoContext.SNACK);

        List<List<IntakeOccurrence>> groups = analyzer.group(List.of(a, b, c));

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0)).containsExactly(a);
        assertThat(groups.get(1)).containsExactly(b, c);
    }

    @Test
    void 빈_입력은_빈_결과를_준다() {
        assertThat(analyzer.group(List.of())).isEmpty();
    }

    @Test
    void 기록이_하나면_그룹도_하나다() {
        var a = intake(BASE, PhotoContext.ALCOHOL);
        assertThat(analyzer.group(List.of(a))).containsExactly(List.of(a));
    }

    // ── classify() ───────────────────────────────────────────

    @Test
    void 직전_혈당이_70_미만이면_처치로_자동_재분류된다() {
        var result = analyzer.classify(PhotoContext.MEAL, 69);

        assertThat(result.effectiveContext()).isEqualTo(PhotoContext.HYPO_TREATMENT);
        assertThat(result.autoReclassifiedAsHypoTreatment()).isTrue();
    }

    @Test
    void 직전_혈당이_정확히_70이면_재분류되지_않는다() {
        var result = analyzer.classify(PhotoContext.MEAL, 70);

        assertThat(result.effectiveContext()).isEqualTo(PhotoContext.MEAL);
        assertThat(result.autoReclassifiedAsHypoTreatment()).isFalse();
    }

    @Test
    void 직전_혈당을_모르면_원래_분류를_그대로_쓴다() {
        var result = analyzer.classify(PhotoContext.SNACK, null);

        assertThat(result.effectiveContext()).isEqualTo(PhotoContext.SNACK);
        assertThat(result.autoReclassifiedAsHypoTreatment()).isFalse();
    }

    @Test
    void 이미_처치로_기록된_경우는_재분류로_치지_않는다() {
        var result = analyzer.classify(PhotoContext.HYPO_TREATMENT, 50);

        assertThat(result.effectiveContext()).isEqualTo(PhotoContext.HYPO_TREATMENT);
        assertThat(result.autoReclassifiedAsHypoTreatment()).isFalse();
    }

    // ── windowFor() ──────────────────────────────────────────

    @Test
    void 분석_창은_맥락별_설정값을_따른다() {
        assertThat(analyzer.windowFor(PhotoContext.MEAL)).isEqualTo(Duration.ofHours(4));
        assertThat(analyzer.windowFor(PhotoContext.SNACK)).isEqualTo(Duration.ofHours(4));
        assertThat(analyzer.windowFor(PhotoContext.HYPO_TREATMENT)).isEqualTo(Duration.ofHours(2));
        assertThat(analyzer.windowFor(PhotoContext.ALCOHOL)).isEqualTo(Duration.ofHours(12));
    }

    // ── detectRebound() ──────────────────────────────────────

    @Test
    void 처치_에피소드에서_분석창_안에_ABOVE_RANGE가_있으면_반동이다() {
        List<GlucoseSample> samples = List.of(
                new GlucoseSample(BASE, 65, ReadingFlag.BELOW_RANGE),
                new GlucoseSample(BASE.plus(Duration.ofMinutes(30)), 110, ReadingFlag.NORMAL),
                new GlucoseSample(BASE.plus(Duration.ofMinutes(60)), 210, ReadingFlag.ABOVE_RANGE));

        assertThat(analyzer.detectRebound(PhotoContext.HYPO_TREATMENT, samples)).isTrue();
    }

    @Test
    void 처치_에피소드여도_ABOVE_RANGE가_없으면_반동이_아니다() {
        List<GlucoseSample> samples = List.of(
                new GlucoseSample(BASE, 65, ReadingFlag.BELOW_RANGE),
                new GlucoseSample(BASE.plus(Duration.ofMinutes(30)), 110, ReadingFlag.NORMAL));

        assertThat(analyzer.detectRebound(PhotoContext.HYPO_TREATMENT, samples)).isFalse();
    }

    @Test
    void 처치_에피소드가_아니면_ABOVE_RANGE가_있어도_반동으로_보지_않는다() {
        List<GlucoseSample> samples = List.of(new GlucoseSample(BASE, 210, ReadingFlag.ABOVE_RANGE));

        assertThat(analyzer.detectRebound(PhotoContext.MEAL, samples)).isFalse();
    }

    @Test
    void 샘플이_없으면_반동이_아니다() {
        assertThat(analyzer.detectRebound(PhotoContext.HYPO_TREATMENT, List.of())).isFalse();
    }
}
