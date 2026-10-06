package com.glucoselog.intake;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import com.glucoselog.photo.PhotoContext;

@Entity
@Table(name = "intake")
public class Intake {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "photo_id")
    private UUID photoId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PhotoContext context;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @OneToMany(mappedBy = "intake", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    private List<IntakeItem> items = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Intake() {
    }

    public Intake(UUID userId, UUID photoId, PhotoContext context, Instant occurredAt) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.photoId = photoId;
        this.context = context;
        this.occurredAt = occurredAt;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(PhotoContext context, Instant occurredAt, Instant now) {
        this.context = context;
        this.occurredAt = occurredAt;
        this.updatedAt = now;
    }

    public void replaceItems(List<IntakeItem> newItems) {
        items.clear();
        for (IntakeItem item : newItems) {
            item.setIntake(this);
            items.add(item);
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getPhotoId() {
        return photoId;
    }

    public PhotoContext getContext() {
        return context;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public List<IntakeItem> getItems() {
        return items;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
