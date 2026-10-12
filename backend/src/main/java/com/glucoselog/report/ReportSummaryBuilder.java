package com.glucoselog.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.glucoselog.episode.EpisodeProperties;
import com.glucoselog.photo.PhotoContext;

/**
 * 본인용 리포트의 고정 문장 틀. 서버가 계산한 사실만 채운다 - AI가 쓰지 않고, 원인·평가·조언·안심 문구는 넣지 않는다
 * (medical-info-policy.md 4장 금지 표현). 문구는 팀 검토 전 초안이며 의료인 검토를 받은 적 없다.
 */
@Component
public class ReportSummaryBuilder {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(KST);

    private final EpisodeProperties episodeProperties;

    public ReportSummaryBuilder(EpisodeProperties episodeProperties) {
        this.episodeProperties = episodeProperties;
    }

    public List<String> daily(
            DailyReportResponse.GlucoseSummary glucose, List<EpisodeSummary> episodes, int insulinEventsCount) {
        List<String> lines = new ArrayList<>();

        if (glucose == null) {
            lines.add("이 날은 올라온 혈당 그래프가 없어요.");
        } else {
            if (glucose.sufficient()) {
                lines.add("이 날 혈당 그래프의 " + percent(glucose.coverageRatio()) + "%를 읽었어요.");
            } else {
                lines.add("이 날 혈당 그래프는 " + percent(glucose.coverageRatio()) + "%만 읽혔어요. 읽힌 구간만 보여드려요.");
            }
            if (glucose.readingsCount() > 0 && glucose.average() != null) {
                lines.add("읽힌 값 " + glucose.readingsCount() + "개 기준, 평균 " + glucose.average().toPlainString()
                        + ", 최저 " + glucose.min() + ", 최고 " + glucose.max() + " mg/dL예요.");
            }
            lines.add("읽히지 않은 구간은 계산에 넣지 않았어요.");
        }

        int intakeCount = episodes.stream().mapToInt(EpisodeSummary::intakeCount).sum();
        lines.add("섭취 기록 " + intakeCount + "건, 인슐린 기록 " + insulinEventsCount + "건이 있어요.");

        for (EpisodeSummary episode : episodes) {
            lines.addAll(episodeLines(episode));
        }

        if (glucose != null) {
            lines.add("그래프 이미지에서 읽은 값이라 실제 측정값과 차이가 있을 수 있어요.");
        }
        if (episodes.size() >= 2 || (!episodes.isEmpty() && insulinEventsCount > 0)) {
            lines.add("다른 섭취·인슐린 기록이 함께 있어 한 기록의 영향으로 구분할 수 없어요.");
        }
        return lines;
    }

    public List<String> weekly(WeeklyReportResponse week) {
        List<String> lines = new ArrayList<>();
        StringBuilder status = new StringBuilder("이번 주는 " + week.sufficientDays() + "일 충분히 읽혔고, "
                + week.lowCoverageDays() + "일은 일부만 읽혔어요.");
        if (week.daysInsufficient() > 0) {
            status.append(" ").append(week.daysInsufficient()).append("일은 올라온 그래프가 없어요.");
        }
        if (week.pendingDays() > 0) {
            status.append(" ").append(week.pendingDays()).append("일은 아직 집계 중이에요.");
        }
        lines.add(status.toString());

        if (week.averageGlucose() != null) {
            lines.add("읽힌 값 기준 평균은 " + week.averageGlucose().toPlainString() + " mg/dL예요. 읽히지 않은 구간은 포함하지 않았어요.");
        }
        lines.add("섭취 기록 묶음 " + week.episodesCount() + "건, 인슐린 기록 " + week.insulinEventsCount() + "건이에요.");
        if (week.reboundCount() > 0) {
            lines.add("처치 기록 뒤 그래프 상단에 닿는 값이 읽힌 경우가 " + week.reboundCount() + "건 있어요.");
        }
        lines.add("일반 목표와의 비교는 지금은 제공하지 않아요.");
        return lines;
    }

    private List<String> episodeLines(EpisodeSummary episode) {
        List<String> lines = new ArrayList<>();
        String time = TIME.format(episode.startAt());
        String label = label(episode.effectiveContext());
        if (episode.intakeCount() > 1) {
            lines.add(time + "부터 " + label + " 기록 " + episode.intakeCount() + "건이 있어요.");
        } else {
            lines.add(time + " " + label + " 기록이 있어요.");
        }

        if (Boolean.TRUE.equals(episode.autoReclassified())) {
            lines.add("직전에 읽힌 값이 " + episodeProperties.hypoGlucoseThreshold() + " 미만이라 처치 기록으로 분류했어요. "
                    + "입력하신 분류는 " + label(episode.originalContext()) + "이에요.");
        }

        if (episode.effectiveContext() == PhotoContext.HYPO_TREATMENT) {
            long hours = Duration.between(episode.startAt(), episode.windowEndAt()).toHours();
            if (episode.reboundDetected() == null) {
                lines.add("처치 기록 뒤 구간에 읽힌 값이 없어서 확인하지 못했어요.");
            } else if (episode.reboundDetected()) {
                lines.add("처치 기록 뒤 " + hours + "시간 안에 그래프 상단에 닿는 값이 읽혔어요.");
            } else {
                lines.add("처치 기록 뒤 " + hours + "시간 동안 읽힌 구간에서는 그래프 상단에 닿는 값이 없었어요.");
            }
        }
        return lines;
    }

    static String label(PhotoContext context) {
        return switch (context) {
            case MEAL -> "식사";
            case SNACK -> "간식";
            case HYPO_TREATMENT -> "저혈당 처치";
            case ALCOHOL -> "술";
        };
    }

    private static String percent(BigDecimal ratio) {
        return ratio.movePointRight(2).setScale(0, RoundingMode.HALF_UP).toPlainString();
    }
}
