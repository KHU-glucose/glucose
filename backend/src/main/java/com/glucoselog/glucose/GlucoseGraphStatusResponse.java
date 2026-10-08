package com.glucoselog.glucose;

import java.math.BigDecimal;
import java.time.LocalDate;

public record GlucoseGraphStatusResponse(LocalDate date, BigDecimal coverageRatio) {
}
