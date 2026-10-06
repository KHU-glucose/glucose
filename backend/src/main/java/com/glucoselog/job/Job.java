package com.glucoselog.job;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "job")
public class Job {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "last_error")
    private String lastError;

    @Column
    private String result;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Job() {
    }

    public Job(String type, String payload, int maxAttempts) {
        Instant now = Instant.now();
        this.id = UUID.randomUUID();
        this.type = type;
        this.payload = payload;
        this.status = JobStatus.PENDING;
        this.attempts = 0;
        this.maxAttempts = maxAttempts;
        this.availableAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void markProcessing(Instant now) {
        this.status = JobStatus.PROCESSING;
        this.updatedAt = now;
    }

    public void markDone(String result, Instant now) {
        this.status = JobStatus.DONE;
        this.result = result;
        this.updatedAt = now;
    }

    /** 재시도 가능한 실패: 시도 횟수를 늘리고, 다 썼으면 FAILED, 아니면 선형 백오프 후 다시 PENDING. */
    public void recordFailure(String error, Instant now, Duration backoffUnit) {
        this.attempts++;
        this.lastError = error;
        this.updatedAt = now;
        if (this.attempts >= this.maxAttempts) {
            this.status = JobStatus.FAILED;
        } else {
            this.status = JobStatus.PENDING;
            this.availableAt = now.plus(backoffUnit.multipliedBy(this.attempts));
        }
    }

    /** 재시도해도 의미 없는 실패(예: 잘못된 요청) — 바로 FAILED. */
    public void failPermanently(String error, Instant now) {
        this.attempts++;
        this.lastError = error;
        this.status = JobStatus.FAILED;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public String getPayload() {
        return payload;
    }

    public JobStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public Instant getAvailableAt() {
        return availableAt;
    }

    public String getLastError() {
        return lastError;
    }

    public String getResult() {
        return result;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
