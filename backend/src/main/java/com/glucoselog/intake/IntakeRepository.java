package com.glucoselog.intake;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IntakeRepository extends JpaRepository<Intake, UUID> {

    Optional<Intake> findByIdAndUserId(UUID id, UUID userId);

    // cursor가 없을 때와 있을 때를 분리한다. "? IS NULL OR ..." 형태로 합치면 null 바인딩 시
    // Postgres가 파라미터 타입을 추론하지 못해 "could not determine data type of parameter"로 깨진다.
    List<Intake> findByUserIdOrderByOccurredAtDescIdDesc(UUID userId, Pageable pageable);

    @Query("SELECT i FROM Intake i WHERE i.userId = :userId "
            + "AND (i.occurredAt < :cursorTime OR (i.occurredAt = :cursorTime AND i.id < :cursorId)) "
            + "ORDER BY i.occurredAt DESC, i.id DESC")
    List<Intake> findPageAfterCursor(
            @Param("userId") UUID userId,
            @Param("cursorTime") Instant cursorTime,
            @Param("cursorId") UUID cursorId,
            Pageable pageable);
}
