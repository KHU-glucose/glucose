package com.glucoselog.insulin;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 입력만 저장한다. 용량 조언·판단은 하지 않는다. */
@Entity
@Table(name = "insulin_event")
public class InsulinEvent {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(nullable = false)
    private BigDecimal units;

    @Column(nullable = false)
    private String kind;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected InsulinEvent() {
    }

    public InsulinEvent(UUID userId, Instant occurredAt, BigDecimal units, String kind) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.occurredAt = occurredAt;
        this.units = units;
        this.kind = kind;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(Instant occurredAt, BigDecimal units, String kind, Instant now) {
        this.occurredAt = occurredAt;
        this.units = units;
        this.kind = kind;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public BigDecimal getUnits() {
        return units;
    }

    public String getKind() {
        return kind;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
