package com.glucoselog.episode;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** CLAUDE.md 분석 규칙의 숫자값. 범위로 적힌 값(60~90분, 식사 3~5h)은 그 범위 안에서 고른 기본값이고
 * 운영 중 바꿀 수 있게 설정으로 뺐다. */
@ConfigurationProperties(prefix = "episode")
public record EpisodeProperties(
        int gapMinutes,
        int mealWindowHours,
        int hypoTreatmentWindowHours,
        int alcoholWindowHours,
        int hypoGlucoseThreshold) {
}
