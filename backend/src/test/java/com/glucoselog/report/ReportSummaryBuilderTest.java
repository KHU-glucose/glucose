package com.glucoselog.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.glucoselog.episode.EpisodeProperties;
import com.glucoselog.photo.PhotoContext;

/** 본인용 문장 틀: 사실만 채우고, 정책 금지 표현이 들어가지 않는지 확인한다. */
class ReportSummaryBuilderTest {

    /** medical-info-policy.md 4장 금지 표현(일부). 새 문구를 추가할 때 이 목록을 넓힌다. */
    private static final List<String> FORBIDDEN = List.of(
            "위험", "정상", "비정상", "늘리세요", "줄이세요", "끊으세요", "잘했어요", "부족해요", "안심", "괜찮", "때문에", "원인");

    private final ReportSummaryBuilder builder = new ReportSummaryBuilder(new EpisodeProperties(90, 4, 2, 12, 70));

    private static DailyReportResponse.GlucoseSummary glucose(String coverage, boolean sufficient) {
        return new DailyReportResponse.GlucoseSummary(
                new BigDecimal(coverage), new BigDecimal("142.3"), 68, 210, 90, sufficient);
    }

    private static EpisodeSummary hypoEpisode(Boolean autoReclassified, Boolean rebound) {
        return new EpisodeSummary(
                Instant.parse("2026-10-01T09:00:00Z"), // 18:00 KST
                PhotoContext.SNACK,
                PhotoContext.HYPO_TREATMENT,
                autoReclassified,
                Instant.parse("2026-10-01T11:00:00Z"),
                rebound,
                1);
    }

    private static void assertNoForbidden(List<String> lines) {
        for (String line : lines) {
            for (String word : FORBIDDEN) {
                assertThat(line).as("금지 표현 '%s'", word).doesNotContain(word);
            }
        }
    }

    @Test
    void 그래프가_없으면_없다고만_쓴다() {
        List<String> lines = builder.daily(null, List.of(), 0);

        assertThat(lines.get(0)).isEqualTo("이 날은 올라온 혈당 그래프가 없어요.");
        assertThat(String.join(" ", lines)).doesNotContain("평균");
        assertNoForbidden(lines);
    }

    @Test
    void 충분한_날은_읽은_비율과_수치를_근거_개수와_함께_쓴다() {
        List<String> lines = builder.daily(glucose("0.94", true), List.of(), 0);

        assertThat(lines).contains(
                "이 날 혈당 그래프의 94%를 읽었어요.",
                "읽힌 값 90개 기준, 평균 142.3, 최저 68, 최고 210 mg/dL예요.",
                "읽히지 않은 구간은 계산에 넣지 않았어요.");
        assertNoForbidden(lines);
    }

    @Test
    void 부족한_날은_일부만_읽혔다고_쓴다() {
        List<String> lines = builder.daily(glucose("0.40", false), List.of(), 0);

        assertThat(lines.get(0)).isEqualTo("이 날 혈당 그래프는 40%만 읽혔어요. 읽힌 구간만 보여드려요.");
    }

    @Test
    void 자동_재분류는_입력한_분류를_보존해서_알려준다() {
        List<String> lines = builder.daily(glucose("0.94", true), List.of(hypoEpisode(true, true)), 1);

        assertThat(lines).contains(
                "18:00 저혈당 처치 기록이 있어요.",
                "직전에 읽힌 값이 70 미만이라 처치 기록으로 분류했어요. 입력하신 분류는 간식이에요.",
                "처치 기록 뒤 2시간 안에 그래프 상단에 닿는 값이 읽혔어요.");
        assertNoForbidden(lines);
    }

    @Test
    void 처치_뒤_읽힌_값이_없으면_확인하지_못했다고_쓰고_없었다고_쓰지_않는다() {
        List<String> lines = builder.daily(glucose("0.94", true), List.of(hypoEpisode(null, null)), 0);

        assertThat(lines).contains("처치 기록 뒤 구간에 읽힌 값이 없어서 확인하지 못했어요.");
        assertThat(String.join(" ", lines)).doesNotContain("값이 없었어요");
    }

    @Test
    void 처치_뒤_상단_값이_없으면_읽힌_구간에서는_없었다고_쓴다() {
        List<String> lines = builder.daily(glucose("0.94", true), List.of(hypoEpisode(false, false)), 0);

        assertThat(lines).contains("처치 기록 뒤 2시간 동안 읽힌 구간에서는 그래프 상단에 닿는 값이 없었어요.");
    }

    @Test
    void 기록이_여러_개면_영향을_구분할_수_없다는_한계를_붙인다() {
        List<String> lines = builder.daily(glucose("0.94", true), List.of(hypoEpisode(false, false)), 1);

        assertThat(lines).contains("다른 섭취·인슐린 기록이 함께 있어 한 기록의 영향으로 구분할 수 없어요.");
    }

    @Test
    void 인슐린은_건수만_쓰고_용량은_쓰지_않는다() {
        List<String> lines = builder.daily(glucose("0.94", true), List.of(), 2);

        assertThat(lines).contains("섭취 기록 0건, 인슐린 기록 2건이 있어요.");
    }

    @Test
    void 주간_문장은_일수만_보여주고_총평_없이_목표_비교는_제공하지_않는다고_쓴다() {
        WeeklyReportResponse week = new WeeklyReportResponse(
                java.time.LocalDate.of(2026, 10, 5), java.time.LocalDate.of(2026, 10, 11),
                3, 1, new BigDecimal("138.2"), 4, 2, 5, 2, 1, 3, List.of());

        List<String> lines = builder.weekly(week);

        assertThat(lines).contains(
                "이번 주는 2일 충분히 읽혔고, 1일은 일부만 읽혔어요. 1일은 올라온 그래프가 없어요. 3일은 아직 집계 중이에요.",
                "읽힌 값 기준 평균은 138.2 mg/dL예요. 읽히지 않은 구간은 포함하지 않았어요.",
                "처치 기록 뒤 그래프 상단에 닿는 값이 읽힌 경우가 2건 있어요.",
                "일반 목표와의 비교는 지금은 제공하지 않아요.");
        assertNoForbidden(lines);
    }
}
