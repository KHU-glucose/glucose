package com.glucoselog.glucose;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "glucose_graph_upload")
public class GlucoseGraphUpload {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "object_key", nullable = false, unique = true)
    private String objectKey;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private GlucoseGraphUploadStatus status;

    @Column(name = "result_date")
    private LocalDate resultDate;

    @Column(name = "coverage_ratio")
    private BigDecimal coverageRatio;

    @Column(name = "parser_version")
    private String parserVersion;

    @Column(name = "parse_job_id")
    private UUID parseJobId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "uploaded_at")
    private Instant uploadedAt;

    protected GlucoseGraphUpload() {
    }

    public GlucoseGraphUpload(UUID userId, String objectKey, String contentType) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.objectKey = objectKey;
        this.contentType = contentType;
        this.status = GlucoseGraphUploadStatus.PENDING_UPLOAD;
        this.createdAt = Instant.now();
    }

    public void markUploaded(Instant now) {
        this.status = GlucoseGraphUploadStatus.UPLOADED;
        this.uploadedAt = now;
    }

    public void setParseJobId(UUID parseJobId) {
        this.parseJobId = parseJobId;
    }

    public void applyParseResult(LocalDate resultDate, BigDecimal coverageRatio, String parserVersion) {
        this.resultDate = resultDate;
        this.coverageRatio = coverageRatio;
        this.parserVersion = parserVersion;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public String getContentType() {
        return contentType;
    }

    public GlucoseGraphUploadStatus getStatus() {
        return status;
    }

    public LocalDate getResultDate() {
        return resultDate;
    }

    public BigDecimal getCoverageRatio() {
        return coverageRatio;
    }

    public String getParserVersion() {
        return parserVersion;
    }

    public UUID getParseJobId() {
        return parseJobId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }
}
