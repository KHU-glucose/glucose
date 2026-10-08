package com.glucoselog.glucose;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record GlucoseReadingsResponse(LocalDate date, BigDecimal coverageRatio, List<ReadingResponse> readings) {

    public record ReadingResponse(String time, Integer value, ReadingFlag flag) {
    }
}
