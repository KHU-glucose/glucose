package com.glucoselog.glucose;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GlucoseGraphUploadRepository extends JpaRepository<GlucoseGraphUpload, UUID> {

    Optional<GlucoseGraphUpload> findByIdAndUserId(UUID id, UUID userId);

    Optional<GlucoseGraphUpload> findByUserIdAndResultDate(UUID userId, LocalDate resultDate);

    List<GlucoseGraphUpload> findByUserIdAndResultDateAndIdNot(UUID userId, LocalDate resultDate, UUID excludedId);
}
