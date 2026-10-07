package com.glucoselog.insulin;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InsulinEventRepository extends JpaRepository<InsulinEvent, UUID> {

    Optional<InsulinEvent> findByIdAndUserId(UUID id, UUID userId);

    List<InsulinEvent> findByUserIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
            UUID userId, Instant from, Instant to);

    List<InsulinEvent> findByUserIdOrderByOccurredAtDescIdDesc(UUID userId, Pageable pageable);

    @Query("SELECT e FROM InsulinEvent e WHERE e.userId = :userId "
            + "AND (e.occurredAt < :cursorTime OR (e.occurredAt = :cursorTime AND e.id < :cursorId)) "
            + "ORDER BY e.occurredAt DESC, e.id DESC")
    List<InsulinEvent> findPageAfterCursor(
            @Param("userId") UUID userId,
            @Param("cursorTime") Instant cursorTime,
            @Param("cursorId") UUID cursorId,
            Pageable pageable);
}
