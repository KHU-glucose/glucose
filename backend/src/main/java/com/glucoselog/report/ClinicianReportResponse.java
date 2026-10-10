package com.glucoselog.report;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.glucoselog.glucose.ReadingFlag;
import com.glucoselog.photo.PhotoContext;

/**
 * 의료인과 기록을 함께 볼 때 쓰는 공유용 리포트. 사실과 데이터의 한계만 담는다 - 진단·소견·평가·조언·교육 카드는 없다.
 * 숫자는 전부 코드가 계산한다(AI 없음).
 */
public record ClinicianReportResponse(
        LocalDate from,
        LocalDate to,
        Instant generatedAt,
        int lowReadingThreshold,
        Totals totals,
        List<Day> days,
        List<String> notes,
        String disclaimer) {

    /** 기간 전체 집계. 읽힌 값이 없으면 average/min/max는 null이다(0이 아니다). */
    public record Totals(
            int periodDays,
            int daysSufficient,
            int daysLowCoverage,
            int daysNoGraph,
            int daysInProgress,
            int readingsCount,
            BigDecimal average,
            Integer min,
            Integer max,
            int lowReadingCount,
            int aboveRangeCount,
            int belowRangeCount,
            int episodesCount,
            int hypoTreatmentEpisodesCount,
            int insulinEventsCount) {
    }

    /** status: SUFFICIENT | LOW_COVERAGE | NO_GRAPH | IN_PROGRESS(오늘 및 이후). */
    public record Day(
            LocalDate date,
            String status,
            BigDecimal coverageRatio,
            int readingsCount,
            int missingCount,
            BigDecimal average,
            Integer min,
            Integer max,
            int lowReadingCount,
            int aboveRangeCount,
            int belowRangeCount,
            List<LowReading> lowReadings,
            List<Episode> episodes,
            List<DailyReportResponse.InsulinEventMarker> insulinEvents) {
    }

    /** 설정 기준({@code lowReadingThreshold}) 미만으로 읽힌 값. */
    public record LowReading(Instant time, int value, ReadingFlag flag) {
    }

    /**
     * 가까운 시각의 섭취 기록 묶음. {@code enteredContext}는 사용자가 입력한 분류이고 {@code autoReclassified}가
     * true면 직전 값 때문에 처치로 분류했다는 뜻이다(null은 판단 불가). {@code aboveRangeObserved}는 처치 뒤
     * 관찰 구간에서 그래프 상단에 닿은 값이 있었는지다(null은 읽힌 값이 없어 확인 불가).
     */
    public record Episode(
            Instant startAt,
            PhotoContext enteredContext,
            PhotoContext effectiveContext,
            Boolean autoReclassified,
            Instant windowEndAt,
            Boolean aboveRangeObserved,
            int intakeCount) {
    }
}
