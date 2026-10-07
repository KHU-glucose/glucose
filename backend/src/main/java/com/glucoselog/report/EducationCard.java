package com.glucoselog.report;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "education_card")
public class EducationCard {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger", nullable = false)
    private EducationCardTrigger trigger;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String body;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected EducationCard() {
    }

    public UUID getId() {
        return id;
    }

    public EducationCardTrigger getTrigger() {
        return trigger;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
