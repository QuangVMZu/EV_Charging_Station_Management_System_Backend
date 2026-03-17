package com.swp391.gr3.ev_management.dto.response;

import lombok.*;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class DiscountPreviewResponse {
    private Long invoiceId;

    private double baseAmount;

    private boolean usePoints;

    private int pointsAvailable;
    private int pointsWillUse;

    private int discountRatePct;     // 1 point = 1%
    private double discountAmount;
    private double finalAmount;
}
