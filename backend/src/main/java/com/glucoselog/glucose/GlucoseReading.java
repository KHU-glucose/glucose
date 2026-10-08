package com.glucoselog.glucose;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "glucose_reading")
public class GlucoseReading {

    @Id
    private UUID id;

    @Column(name = "upload_id", nullable = false)
    private UUID uploadId;

    @Column(name = "time_slot", nullable = false)
    private int timeSlot;

    @Column(name = "value")
    private Integer value;

    @Enumerated(EnumType.STRING)
    @Column(name = "flag", nullable = false)
    private ReadingFlag flag;

    protected GlucoseReading() {
    }

    public GlucoseReading(UUID uploadId, int timeSlot, Integer value, ReadingFlag flag) {
        this.id = UUID.randomUUID();
        this.uploadId = uploadId;
        this.timeSlot = timeSlot;
        this.value = value;
        this.flag = flag;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUploadId() {
        return uploadId;
    }

    public int getTimeSlot() {
        return timeSlot;
    }

    public Integer getValue() {
        return value;
    }

    public ReadingFlag getFlag() {
        return flag;
    }
}
