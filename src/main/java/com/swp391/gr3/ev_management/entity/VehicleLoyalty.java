package com.swp391.gr3.ev_management.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "vehicle_loyalty",
        uniqueConstraints = @UniqueConstraint(name="uk_vehicle_loyalty_vehicle", columnNames = "vehicle_id"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VehicleLoyalty {

    @Id
    @Column(name = "vehicle_id")
    private Long vehicleId; // PK = vehicleId => 1 vehicle chỉ 1 dòng

    @Column(name = "points_balance", nullable = false)
    private int pointsBalance = 0; // 0..100

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    private Long version; // chống race condition (optimistic lock)

    @PreUpdate
    @PrePersist
    void touch() { this.updatedAt = Instant.now(); }

    public void addPoint(int n) {
        this.pointsBalance = Math.min(100, this.pointsBalance + n);
    }

    public void reset() { this.pointsBalance = 0; }

}