package com.glucoselog.report;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.glucoselog.episode.GlucoseSample;
import com.glucoselog.glucose.GlucoseGraphUpload;
import com.glucoselog.glucose.GlucoseGraphUploadRepository;
import com.glucoselog.glucose.GlucoseReadingRepository;

/** glucose_graph_upload/glucose_reading을 리포트·에피소드 분석이 쓰기 좋은 절대시각 샘플로 바꾼다.
 * ml-service가 읽은 readings[].time은 한국 시간 기준이다(docs/ml-service-contract.md). */
@Service
public class GlucoseTimelineService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final GlucoseGraphUploadRepository uploadRepository;
    private final GlucoseReadingRepository readingRepository;

    public GlucoseTimelineService(GlucoseGraphUploadRepository uploadRepository, GlucoseReadingRepository readingRepository) {
        this.uploadRepository = uploadRepository;
        this.readingRepository = readingRepository;
    }

    /** 해당 날짜에 분석 완료된 그래프가 있으면 coverage_ratio와 샘플을, 없으면 empty를 돌려준다. */
    public Optional<DaySummary> findDaySummary(UUID userId, LocalDate date) {
        return uploadRepository.findByUserIdAndResultDate(userId, date)
                .map(upload -> new DaySummary(upload.getCoverageRatio(), toSamples(upload)));
    }

    /** [from, to] 범위(양끝 포함)의 샘플을 날짜 순으로 이어붙인다. 데이터 없는 날은 조용히 건너뛴다. */
    public List<GlucoseSample> loadRange(UUID userId, LocalDate from, LocalDate to) {
        List<GlucoseSample> samples = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            uploadRepository.findByUserIdAndResultDate(userId, date)
                    .ifPresent(upload -> samples.addAll(toSamples(upload)));
        }
        samples.sort(Comparator.comparing(GlucoseSample::time));
        return samples;
    }

    private List<GlucoseSample> toSamples(GlucoseGraphUpload upload) {
        LocalDate date = upload.getResultDate();
        return readingRepository.findByUploadIdOrderByTimeSlot(upload.getId()).stream()
                .map(reading -> new GlucoseSample(
                        date.atStartOfDay(KST).plusMinutes(reading.getTimeSlot()).toInstant(),
                        reading.getValue(),
                        reading.getFlag()))
                .toList();
    }

    public record DaySummary(java.math.BigDecimal coverageRatio, List<GlucoseSample> samples) {
    }
}
