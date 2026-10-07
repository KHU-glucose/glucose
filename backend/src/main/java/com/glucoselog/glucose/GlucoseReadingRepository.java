package com.glucoselog.glucose;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GlucoseReadingRepository extends JpaRepository<GlucoseReading, UUID> {

    List<GlucoseReading> findByUploadIdOrderByTimeSlot(UUID uploadId);

    void deleteByUploadId(UUID uploadId);
}
