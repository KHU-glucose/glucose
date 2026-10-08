package com.glucoselog.photo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IntakePhotoRepository extends JpaRepository<IntakePhoto, UUID> {

    Optional<IntakePhoto> findByIdAndUserId(UUID id, UUID userId);

    @Query("SELECT p.recognitionJobId FROM IntakePhoto p WHERE p.userId = :userId AND p.recognitionJobId IS NOT NULL")
    List<UUID> findRecognitionJobIdsByUserId(@Param("userId") UUID userId);
}
