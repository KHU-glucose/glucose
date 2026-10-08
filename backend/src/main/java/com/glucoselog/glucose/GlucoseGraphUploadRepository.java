package com.glucoselog.glucose;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GlucoseGraphUploadRepository extends JpaRepository<GlucoseGraphUpload, UUID> {

    Optional<GlucoseGraphUpload> findByIdAndUserId(UUID id, UUID userId);

    Optional<GlucoseGraphUpload> findByUserIdAndResultDate(UUID userId, LocalDate resultDate);

    List<GlucoseGraphUpload> findByUserIdAndResultDateAndIdNot(UUID userId, LocalDate resultDate, UUID excludedId);

    @Query("SELECT u.parseJobId FROM GlucoseGraphUpload u WHERE u.userId = :userId AND u.parseJobId IS NOT NULL")
    List<UUID> findParseJobIdsByUserId(@Param("userId") UUID userId);
}
