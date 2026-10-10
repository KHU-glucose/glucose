package com.glucoselog.report;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 리포트 설정. sufficientCoverageRatio는 "기록 충분" 표시의 임시 제품 기준이다(의학적 기준 아님). */
@ConfigurationProperties(prefix = "report")
public record ReportProperties(BigDecimal sufficientCoverageRatio) {
}
