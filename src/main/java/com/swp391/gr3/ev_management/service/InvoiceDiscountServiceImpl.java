package com.swp391.gr3.ev_management.service;

import com.swp391.gr3.ev_management.dto.response.DiscountPreviewResponse;
import com.swp391.gr3.ev_management.entity.ChargingSession;
import com.swp391.gr3.ev_management.entity.Invoice;
import com.swp391.gr3.ev_management.entity.UserVehicle;
import com.swp391.gr3.ev_management.entity.VehicleLoyalty;
import com.swp391.gr3.ev_management.enums.InvoiceStatus;
import com.swp391.gr3.ev_management.exception.ConflictException;
import com.swp391.gr3.ev_management.exception.ErrorException;
import com.swp391.gr3.ev_management.repository.InvoiceRepository;
import com.swp391.gr3.ev_management.repository.VehicleLoyaltyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class InvoiceDiscountServiceImpl implements InvoiceDiscountService {

    private final InvoiceRepository invoiceRepository;
    private final VehicleLoyaltyRepository loyaltyRepository;

    // 1 point = 1% (max 100%)
    private static int clampRate(int points) {
        return Math.max(0, Math.min(100, points));
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double nz(Double v) {
        return v == null ? 0.0 : v;
    }

    private static double normalizeMoney(double v) {
        // tránh -0.0
        if (v == -0.0) return 0.0;
        // tránh NaN/Infinity
        if (!Double.isFinite(v)) return 0.0;
        // tránh âm
        return Math.max(0.0, v);
    }

    private int getPointsAvailable(Long vehicleId) {
        return loyaltyRepository.findById(vehicleId)
                .map(VehicleLoyalty::getPointsBalance)
                .orElse(0);
    }

    @Override
    public Long resolveVehicleIdFromInvoice(Invoice invoice) {
        ChargingSession session = invoice.getSession();
        if (session == null || session.getBooking() == null) {
            throw new ErrorException("Invoice missing session/booking");
        }
        UserVehicle vehicle = session.getBooking().getVehicle();
        if (vehicle == null) throw new ErrorException("Booking missing vehicle");
        return vehicle.getVehicleId();
    }

    /**
     * ✅ QUY TẮC ĐÚNG:
     * base = invoice.amount (giá gốc) hoặc invoice.baseAmount nếu bạn có field này.
     * discountAmount = base * rate%
     * finalAmount = base - discountAmount
     *
     * ❌ KHÔNG BAO GIỜ dùng invoice.discountAmount làm base.
     */
    private double resolveBaseAmount(Invoice invoice) {
        // Nếu bạn có field baseAmount thì ưu tiên dùng (uncomment khi entity có)
        // Double baseDb = invoice.getBaseAmount();
        // double base = baseDb != null ? baseDb : nz(invoice.getAmount());

        // Nếu chưa có baseAmount -> dùng amount làm base luôn
        double base = nz(invoice.getAmount());

        base = normalizeMoney(base);
        return base;
    }

    @Override
    public DiscountPreviewResponse preview(Long invoiceId, boolean usePoints) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ErrorException("Invoice not found"));

        double base = resolveBaseAmount(invoice);

        Long vehicleId = resolveVehicleIdFromInvoice(invoice);
        int available = getPointsAvailable(vehicleId);

        // ✅ dùng tối đa 100 điểm vì max 100%
        int willUse = usePoints ? Math.min(available, 100) : 0;
        int rate = usePoints ? clampRate(willUse) : 0;

        double discount = usePoints ? round2(base * rate / 100.0) : 0.0;
        discount = normalizeMoney(discount);

        double finalAmount = round2(base - discount);
        finalAmount = normalizeMoney(finalAmount);

        log.info("[DISCOUNT PREVIEW] invoiceId={} base={} usePoints={} available={} willUse={} rate={} discount={} final={}",
                invoiceId, base, usePoints, available, willUse, rate, discount, finalAmount);

        return DiscountPreviewResponse.builder()
                .invoiceId(invoice.getInvoiceId())
                .baseAmount(base)
                .usePoints(usePoints)
                .pointsAvailable(available)
                .pointsWillUse(willUse)
                .discountRatePct(rate)
                .discountAmount(discount)
                .finalAmount(finalAmount)
                .build();
    }

    @Override
    @Transactional
    public Invoice apply(Long invoiceId, boolean usePoints) {
        Invoice invoice = invoiceRepository.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new ErrorException("Invoice not found"));

        if (invoice.getStatus() != InvoiceStatus.UNPAID && invoice.getStatus() != InvoiceStatus.PENDING) {
            throw new ConflictException("Invoice #" + invoiceId
                    + " is not UNPAID (current: " + invoice.getStatus() + ")");
        }

        double base = resolveBaseAmount(invoice);

        Long vehicleId = resolveVehicleIdFromInvoice(invoice);
        int available = getPointsAvailable(vehicleId);

        int willUse = usePoints ? Math.min(available, 100) : 0;
        int rate = usePoints ? clampRate(willUse) : 0;

        double discount = usePoints ? round2(base * rate / 100.0) : 0.0;
        discount = normalizeMoney(discount);

        double finalAmount = round2(base - discount);
        finalAmount = normalizeMoney(finalAmount);

        invoice.setUsePointsSelected(usePoints);
        invoice.setUsedPoints(willUse);
        invoice.setDiscountRatePct(rate);

        // ✅ discountAmount là "tiền giảm", không phải base
        invoice.setDiscountAmount(discount);

        // ✅ finalAmount là số tiền phải trả
        invoice.setFinalAmount(finalAmount);

        // Nếu bạn có baseAmount thì set:
        // invoice.setBaseAmount(base);

        log.info("[DISCOUNT APPLY] invoiceId={} base={} usePoints={} available={} willUse={} rate={} discount={} final={}",
                invoiceId, base, usePoints, available, willUse, rate, discount, finalAmount);

        return invoiceRepository.save(invoice);
    }
}
