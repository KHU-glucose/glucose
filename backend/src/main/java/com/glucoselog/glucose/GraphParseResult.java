package com.glucoselog.glucose;

import java.math.BigDecimal;
import java.util.List;

/** ml-service 응답(docs/ml-service-contract.md) 중 백엔드가 쓰는 필드만 추린 것.
 * gaps, meta.image_width 같은 필드는 그대로 두면 Jackson이 알려지지 않은 필드로 무시한다. */
public record GraphParseResult(String date, List<Reading> readings, BigDecimal coverageRatio, Meta meta) {

    public record Reading(String time, Integer value, String flag) {
    }

    public record Meta(String parserVersion) {
    }
}
