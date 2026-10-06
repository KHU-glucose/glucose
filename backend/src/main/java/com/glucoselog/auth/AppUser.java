package com.glucoselog.auth;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    private UUID id;

    @Column(name = "apple_sub", nullable = false, unique = true)
    private String appleSub;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AppUser() {
    }

    public AppUser(String appleSub) {
        this.id = UUID.randomUUID();
        this.appleSub = appleSub;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getAppleSub() {
        return appleSub;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
