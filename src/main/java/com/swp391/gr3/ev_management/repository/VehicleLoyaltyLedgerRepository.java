package com.swp391.gr3.ev_management.repository;

import com.swp391.gr3.ev_management.entity.VehicleLoyaltyLedger;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VehicleLoyaltyLedgerRepository extends JpaRepository<VehicleLoyaltyLedger, Long> {

    boolean existsByTransactionIdAndType(Long transactionId, VehicleLoyaltyLedger.Type type);

    boolean existsByInvoiceIdAndType(Long invoiceId, VehicleLoyaltyLedger.Type type);
}
