package com.glucoselog.photo;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IntakePhotoRepository extends JpaRepository<IntakePhoto, UUID> {

    Optional<IntakePhoto> findByIdAndUserId(UUID id, UUID userId);
}
