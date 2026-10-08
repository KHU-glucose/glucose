package com.glucoselog.episode;

import java.time.Instant;

import com.glucoselog.glucose.ReadingFlag;

/** 에피소드 분석에 넣는 혈당 값 하나. glucose_reading에서 가져온다. */
public record GlucoseSample(Instant time, Integer value, ReadingFlag flag) {
}
