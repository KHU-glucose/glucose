package com.glucoselog.glucose;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.glucoselog.common.ApiException;

@Service
public class GlucoseReadingService {

    private final GlucoseGraphUploadRepository uploadRepository;
    private final GlucoseReadingRepository readingRepository;

    public GlucoseReadingService(GlucoseGraphUploadRepository uploadRepository, GlucoseReadingRepository readingRepository) {
        this.uploadRepository = uploadRepository;
        this.readingRepository = readingRepository;
    }

    public GlucoseReadingsResponse getByDate(UUID userId, LocalDate date) {
        GlucoseGraphUpload upload = uploadRepository.findByUserIdAndResultDate(userId, date)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "GLUCOSE_DAY_NOT_FOUND", "해당 날짜의 혈당 데이터가 없습니다"));

        List<GlucoseReadingsResponse.ReadingResponse> readings = readingRepository.findByUploadIdOrderByTimeSlot(upload.getId())
                .stream()
                .map(r -> new GlucoseReadingsResponse.ReadingResponse(toTimeString(r.getTimeSlot()), r.getValue(), r.getFlag()))
                .toList();

        return new GlucoseReadingsResponse(date, upload.getCoverageRatio(), readings);
    }

    private static String toTimeString(int timeSlot) {
        return LocalTime.of(timeSlot / 60, timeSlot % 60).toString();
    }
}
