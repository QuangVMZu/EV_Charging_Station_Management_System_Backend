package com.swp391.gr3.ev_management.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.time.LocalDateTime;

@Entity
@Table(name = "vehicle_loyalty_ledger",
        indexes = {
                @Index(name="idx_ledger_vehicle", columnList="vehicle_id"),
                @Index(name="idx_ledger_invoice", columnList="invoice_id"),
                @Index(name="idx_ledger_booking", columnList="booking_id")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VehicleLoyaltyLedger {

    public enum Type { EARN, SPEND, ADJUST }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name="vehicle_id", nullable=false)
    private Long vehicleId;

    @Column(name="booking_id")
    private Long bookingId;

    @Column(name="invoice_id")
    private Long invoiceId;

    @Column(name="transaction_id")
    private Long transactionId;

    @Enumerated(EnumType.STRING)
    @Column(name="type", nullable=false, length=10)
    private Type type;

    @Column(name="points", nullable=false)
    private int points;

    @Column(name="note")
    private String note;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

}
