package com.swp391.gr3.ev_management.service;

import com.swp391.gr3.ev_management.entity.*;
import com.swp391.gr3.ev_management.enums.InvoiceStatus;
import com.swp391.gr3.ev_management.enums.TransactionStatus;
import com.swp391.gr3.ev_management.repository.VehicleLoyaltyLedgerRepository;
import com.swp391.gr3.ev_management.repository.VehicleLoyaltyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class VehicleLoyaltyServiceImpl implements  VehicleLoyaltyService{

    private final VehicleLoyaltyRepository loyaltyRepo;
    private final VehicleLoyaltyLedgerRepository ledgerRepo;

    @Override
    public void earnPointIfEligible(Transaction tx, Invoice invoice) {
        if (tx.getStatus() != TransactionStatus.COMPLETED) return;
        if (invoice.getStatus() != InvoiceStatus.PAID) return;

        ChargingSession session = invoice.getSession();
        if (session == null || session.getBooking() == null) return;

        UserVehicle vehicle = session.getBooking().getVehicle();
        if (vehicle == null) return;

        Long vehicleId = vehicle.getVehicleId();

        // ❌ tránh cộng trùng: check ledger
        boolean earned = ledgerRepo.existsByTransactionIdAndType(
                tx.getTransactionId(),
                VehicleLoyaltyLedger.Type.EARN
        );
        if (earned) return;

        // 🔒 lock row vehicle loyalty
        VehicleLoyalty wallet = loyaltyRepo.findByVehicleIdForUpdate(vehicleId)
                .orElseGet(() -> loyaltyRepo.save(
                        VehicleLoyalty.builder()
                                .vehicleId(vehicleId)
                                .pointsBalance(0)
                                .build()
                ));

        wallet.addPoint(1);
        loyaltyRepo.save(wallet);

        // ghi ledger
        ledgerRepo.save(
                VehicleLoyaltyLedger.builder()
                        .vehicleId(vehicleId)
                        .bookingId(session.getBooking().getBookingId())
                        .invoiceId(invoice.getInvoiceId())
                        .transactionId(tx.getTransactionId())
                        .type(VehicleLoyaltyLedger.Type.EARN)
                        .points(1)
                        .note("Payment success → earn 1 point")
                        .createdAt(LocalDateTime.now())
                        .build()
        );
    }

    @Override
    public int getPointsBalance(Long vehicleId) {
        Integer balance = loyaltyRepo.getPointsBalanceByVehicleId(vehicleId);
        return balance != null ? balance : 0;
    }
}
