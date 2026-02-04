package com.swp391.gr3.ev_management.service;

import com.swp391.gr3.ev_management.entity.*;
import com.swp391.gr3.ev_management.enums.InvoiceStatus;
import com.swp391.gr3.ev_management.enums.TransactionStatus;
import com.swp391.gr3.ev_management.exception.ErrorException;
import com.swp391.gr3.ev_management.repository.InvoiceRepository;
import com.swp391.gr3.ev_management.repository.TransactionRepository;
import com.swp391.gr3.ev_management.repository.VehicleLoyaltyLedgerRepository;
import com.swp391.gr3.ev_management.repository.VehicleLoyaltyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class LoyaltyFinalizeServiceImpl implements LoyaltyFinalizeService {

    private final VehicleLoyaltyRepository loyaltyRepository;
    private final VehicleLoyaltyLedgerRepository ledgerRepository;
    private final InvoiceRepository invoiceRepository;
    private final TransactionRepository transactionRepository;

    private Long resolveVehicleId(Invoice inv) {
        ChargingSession session = inv.getSession();
        if (session == null || session.getBooking() == null) throw new ErrorException("Invoice missing session/booking");
        UserVehicle vehicle = session.getBooking().getVehicle();
        if (vehicle == null) throw new ErrorException("Invoice missing vehicle");
        return vehicle.getVehicleId();
    }

    @Override
    @Transactional
    public void finalizeOnPaymentSuccess(Long transactionId, Long invoiceId) {

        log.warn("[LOYALTY FINALIZE ENTER] txId={} invoiceId={}", transactionId, invoiceId);

        Transaction tx = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new ErrorException("Transaction not found: " + transactionId));

        Invoice inv = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ErrorException("Invoice not found: " + invoiceId));

        log.warn("[LOYALTY FINALIZE LOAD] txStatus={} invoiceStatus={} useSelected={} usedPoints={}",
                tx.getStatus(), inv.getStatus(), inv.isUsePointsSelected(), inv.getUsedPoints());

        // Only when payment success
        if (tx.getStatus() != TransactionStatus.COMPLETED) {
            log.warn("[LOYALTY FINALIZE EXIT] tx not COMPLETED");
            return;
        }
        if (inv.getStatus() != InvoiceStatus.PAID) {
            log.warn("[LOYALTY FINALIZE EXIT] invoice not PAID");
            return;
        }

        Long vehicleId = resolveVehicleId(inv);
        Long bookingId = inv.getSession().getBooking().getBookingId();

        // =========================
        // CASE 1: user USED points => RESET ALL points (spend all)
        // =========================
        if (inv.isUsePointsSelected()) {

            // Idempotent: VNPay may callback multiple times
            boolean spentAlready = ledgerRepository.existsByInvoiceIdAndType(
                    inv.getInvoiceId(), VehicleLoyaltyLedger.Type.SPEND
            );
            if (spentAlready) {
                log.warn("[LOYALTY SPEND SKIP] invoiceId={} already SPEND", inv.getInvoiceId());
                return;
            }

            VehicleLoyalty wallet = loyaltyRepository.findByVehicleIdForUpdate(vehicleId)
                    .orElseGet(() -> loyaltyRepository.save(
                            VehicleLoyalty.builder().vehicleId(vehicleId).pointsBalance(0).build()
                    ));

            int before = wallet.getPointsBalance();

            // ✅ rule: used = used ALL
            wallet.reset();
            loyaltyRepository.save(wallet);

            // (optional) keep invoice consistent for UI/trace
            inv.setUsedPoints(before);
            inv.setDiscountRatePct(Math.min(100, before));
            invoiceRepository.save(inv);

            VehicleLoyaltyLedger spendRow = VehicleLoyaltyLedger.builder()
                    .vehicleId(vehicleId)
                    .bookingId(bookingId)
                    .invoiceId(inv.getInvoiceId())
                    .transactionId(tx.getTransactionId())
                    .type(VehicleLoyaltyLedger.Type.SPEND)
                    .points(before)
                    .note("SPEND ALL points (balance " + before + " -> 0)")
                    .build();

            ledgerRepository.save(spendRow);

            log.warn("[LOYALTY SPEND DONE] vehicleId={} balance {} -> 0", vehicleId, before);
            return; // DO NOT EARN
        }

        // =========================
        // CASE 2: NOT used points => EARN +1
        // =========================
        boolean earned = ledgerRepository.existsByTransactionIdAndType(
                tx.getTransactionId(), VehicleLoyaltyLedger.Type.EARN
        );
        if (earned) {
            log.warn("[LOYALTY EARN SKIP] txId={} already EARN", tx.getTransactionId());
            return;
        }

        VehicleLoyalty wallet = loyaltyRepository.findByVehicleIdForUpdate(vehicleId)
                .orElseGet(() -> loyaltyRepository.save(
                        VehicleLoyalty.builder().vehicleId(vehicleId).pointsBalance(0).build()
                ));

        int before = wallet.getPointsBalance();
        wallet.addPoint(1);
        loyaltyRepository.save(wallet);

        VehicleLoyaltyLedger earnRow = VehicleLoyaltyLedger.builder()
                .vehicleId(vehicleId)
                .bookingId(bookingId)
                .invoiceId(inv.getInvoiceId())
                .transactionId(tx.getTransactionId())
                .type(VehicleLoyaltyLedger.Type.EARN)
                .points(1)
                .note("EARN 1 point (balance " + before + " -> " + wallet.getPointsBalance() + ")")
                .build();

        ledgerRepository.save(earnRow);

        log.warn("[LOYALTY EARN DONE] vehicleId={} {}->{}", vehicleId, before, wallet.getPointsBalance());
    }
}
