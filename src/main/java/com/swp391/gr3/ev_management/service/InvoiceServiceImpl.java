package com.swp391.gr3.ev_management.service;

import com.swp391.gr3.ev_management.dto.response.DriverInvoiceDetail;
import com.swp391.gr3.ev_management.dto.response.UnpaidInvoiceResponse;
import com.swp391.gr3.ev_management.entity.*;
import com.swp391.gr3.ev_management.enums.InvoiceStatus;
import com.swp391.gr3.ev_management.enums.PaymentProvider;
import com.swp391.gr3.ev_management.enums.TransactionStatus;
import com.swp391.gr3.ev_management.mapper.DriverInvoiceMapper;
import com.swp391.gr3.ev_management.repository.InvoiceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class InvoiceServiceImpl implements InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final TariffService tariffService;
    private final DriverInvoiceMapper mapper;
    private final TransactionService transactionService;
    private final ChargingPointService chargingPointService;

    // ✅ ADD: finalize loyalty (reset all if used points, earn +1 if not)
    private final LoyaltyFinalizeService loyaltyFinalizeService;

    @Override
    public Invoice save(Invoice invoice) {
        return invoiceRepository.save(invoice);
    }

    @Override
    public Optional<Invoice> findBySession_SessionId(Long sessionId) {
        return invoiceRepository.findBySession_SessionId(sessionId);
    }

    @Override
    public Optional<Invoice> findById(Long invoiceId) {
        return invoiceRepository.findById(invoiceId);
    }

    @Override
    public List<Invoice> findUnpaidInvoicesByStation(Long stationId) {
        return invoiceRepository.findUnpaidInvoicesByStation(stationId);
    }

    @Override
    public double sumAll() {
        return invoiceRepository.sumAll();
    }

    @Override
    public double sumAmountBetween(LocalDateTime dayFrom, LocalDateTime dayTo) {
        return invoiceRepository.sumAmountBetween(dayFrom, dayTo);
    }

    @Override
    public double sumByStationBetween(Long stationId, LocalDateTime dayFrom, LocalDateTime dayTo) {
        return invoiceRepository.sumByStationBetween(stationId, dayFrom, dayTo);
    }

    @Override
    public DriverInvoiceDetail getDetail(Long invoiceId, Long userId) {
        Invoice invoice = invoiceRepository.findInvoiceDetail(invoiceId)
                .orElseThrow(() -> new RuntimeException("Invoice not found"));

        Booking booking = invoice.getSession().getBooking();

        if (!booking.getVehicle().getDriver().getUser().getUserId().equals(userId)) {
            throw new AccessDeniedException("Forbidden");
        }

        ChargingPoint cp = booking.getBookingSlots().stream()
                .findFirst()
                .map(bs -> bs.getSlot().getChargingPoint())
                .orElse(null);

        if (cp == null || cp.getConnectorType() == null) {
            throw new RuntimeException("ChargingPoint/ConnectorType not found for invoice detail");
        }

        Long connectorTypeId = cp.getConnectorType().getConnectorTypeId();

        Double pricePerKwh = tariffService.findTariffByConnectorType(connectorTypeId)
                .stream()
                .findFirst()
                .map(Tariff::getPricePerKWh)
                .orElse(null);

        return mapper.toDto(invoice, booking, cp, pricePerKwh);
    }

    @Override
    public List<UnpaidInvoiceResponse> getUnpaidInvoices(Long userId) {
        return invoiceRepository.findUnpaidByUserId(userId);
    }

    // ================== DETAIL DÙNG CHUNG ==================
    private DriverInvoiceDetail buildInvoiceDetail(Invoice invoice) {
        Booking booking = invoice.getSession().getBooking();

        ChargingPoint cp = booking.getBookingSlots().stream()
                .filter(bs -> bs.getSlot() != null && bs.getSlot().getChargingPoint() != null)
                .map(bs -> bs.getSlot().getChargingPoint())
                .findFirst()
                .orElse(null);

        if (cp == null) {
            cp = chargingPointService
                    .findFirstByStation_StationId(booking.getStation().getStationId())
                    .orElse(null);
        }

        var connectorType = (cp != null && cp.getConnectorType() != null)
                ? cp.getConnectorType()
                : (booking.getVehicle() != null
                && booking.getVehicle().getModel() != null
                && booking.getVehicle().getModel().getConnectorType() != null)
                ? booking.getVehicle().getModel().getConnectorType()
                : null;

        Double pricePerKwh = null;
        if (connectorType != null) {
            Long connectorTypeId = connectorType.getConnectorTypeId();
            pricePerKwh = tariffService.findTariffByConnectorType(connectorTypeId)
                    .stream()
                    .findFirst()
                    .map(Tariff::getPricePerKWh)
                    .orElse(null);
        } else {
            log.warn("[INVOICE_DETAIL] Cannot resolve connectorType / pricePerKWh for invoiceId={}",
                    invoice.getInvoiceId());
        }

        return mapper.toDto(invoice, booking, cp, pricePerKwh);
    }

    @Override
    public DriverInvoiceDetail getInvoiceDetail(Long invoiceId) {
        Invoice invoice = invoiceRepository.findInvoiceDetail(invoiceId)
                .orElseThrow(() -> new RuntimeException("Invoice not found"));
        return buildInvoiceDetail(invoice);
    }

    // ================== PAY (CASH/EVM) + FINALIZE LOYALTY ==================
    @Override
    @Transactional
    public DriverInvoiceDetail payInvoice(Long invoiceId) {
        Invoice invoice = invoiceRepository.findInvoiceDetail(invoiceId)
                .orElseThrow(() -> new RuntimeException("Invoice not found"));

        if (invoice.getStatus() == InvoiceStatus.PAID) {
            throw new RuntimeException("Invoice already paid");
        }

        // ⚠️ Nếu bạn có method CASH riêng thì tìm theo CASH.
        // Hiện tại bạn dùng EVM method cho giao dịch tại quầy.
        PaymentMethod method = transactionService.findByProvider(PaymentProvider.EVM)
                .orElseThrow(() -> new RuntimeException("Payment method EVM not found"));

        // ✅ số tiền phải trả: ưu tiên finalAmount (sau apply-discount)
        double payable = invoice.getFinalAmount() != null ? invoice.getFinalAmount() : invoice.getAmount();

        if (invoice.getFinalAmount() == null) {
            invoice.setFinalAmount(invoice.getAmount());
        }

        invoice.setStatus(InvoiceStatus.PAID);
        invoice.setUpdatedAt(LocalDateTime.now());
        invoiceRepository.save(invoice);

        Transaction transaction = Transaction.builder()
                .amount(payable)
                .currency(invoice.getCurrency())
                .description("Thanh toán hóa đơn #" + invoice.getInvoiceId() + " qua CASH")
                .status(TransactionStatus.COMPLETED)
                .invoice(invoice)
                .driver(invoice.getDriver())
                .paymentMethod(method)
                .build();

        // ✅ UPSERT để tránh trùng invoiceId
        Transaction savedTx = transactionService.addTransaction(transaction);

        // ✅ FIX QUAN TRỌNG: finalize loyalty cho CASH luôn
        loyaltyFinalizeService.finalizeOnPaymentSuccess(savedTx.getTransactionId(), invoice.getInvoiceId());

        return buildInvoiceDetail(invoice);
    }

    @Override
    public List<DriverInvoiceDetail> getInvoiceDetailsByStation(Long stationId) {
        List<Invoice> invoices = invoiceRepository.findInvoiceDetailsByStation(stationId);

        return invoices.stream()
                .map(this::buildInvoiceDetail)
                .toList();
    }
}
