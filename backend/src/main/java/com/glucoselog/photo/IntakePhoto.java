package com.glucoselog.photo;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "intake_photo")
public class IntakePhoto {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "object_key", nullable = false, unique = true)
    private String objectKey;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "context")
    private PhotoContext context;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PhotoStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "uploaded_at")
    private Instant uploadedAt;

    protected IntakePhoto() {
    }

    public IntakePhoto(UUID userId, String objectKey, String contentType, PhotoContext context) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.objectKey = objectKey;
        this.contentType = contentType;
        this.context = context;
        this.status = PhotoStatus.PENDING_UPLOAD;
        this.createdAt = Instant.now();
    }

    public void markUploaded(Instant now) {
        this.status = PhotoStatus.UPLOADED;
        this.uploadedAt = now;
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

    public PhotoContext getContext() {
        return context;
    }

    public PhotoStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }
}
