package com.swp391.gr3.ev_management.service;

import com.swp391.gr3.ev_management.entity.*;
import com.swp391.gr3.ev_management.enums.*;
import com.swp391.gr3.ev_management.events.NotificationCreatedEvent;
import com.swp391.gr3.ev_management.exception.ConflictException;
import com.swp391.gr3.ev_management.exception.ErrorException;
import com.swp391.gr3.ev_management.repository.ChargingSessionRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final InvoiceService invoiceService;
    private final TransactionService transactionService;
    private final DriverService driverService;
    private final PaymentMethodService paymentMethodService;
    private final ChargingSessionRepository chargingSessionRepository;

    private final NotificationsService notificationsService;
    private final ApplicationEventPublisher eventPublisher;

    private final InvoiceDiscountService invoiceDiscountService;
    private final LoyaltyFinalizeService loyaltyFinalizeService;

    private static final ZoneId TENANT_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Value("${vnpay.tmnCode}")
    private String tmnCode;

    @Value("${vnpay.secretKey}")
    private String secretKey;

    @Value("${vnpay.endpoint:https://sandbox.vnpayment.vn/paymentv2/vpcpay.html}")
    private String vnpUrl;

    @Value("${vnpay.returnUrl}")
    private String returnUrl;

    private double resolvePayAmount(Invoice invoice) {
        if (invoice.getFinalAmount() != null && invoice.getFinalAmount() >= 0) {
            return invoice.getFinalAmount();
        }
        double amt = invoice.getAmount();
        return Double.isFinite(amt) && amt >= 0 ? amt : 0.0;
    }

    @Transactional
    public String createVnPayPaymentUrl(Long userId,
                                        Long sessionId,
                                        Long paymentMethodId,
                                        String clientIp,
                                        boolean usePoints) throws Exception {

        Driver driver = driverService.findByUser_UserId(userId).orElse(null);

        Invoice invoice = invoiceService.findBySession_SessionId(sessionId)
                .orElseThrow(() -> new ErrorException(
                        "No invoice found for session " + sessionId + ". Stop session must create an UNPAID invoice first."));

        PaymentMethod method = paymentMethodService.findById(paymentMethodId)
                .orElseThrow(() -> new ErrorException("Payment method not found"));

        if (invoice.getStatus() != InvoiceStatus.UNPAID) {
            throw new ConflictException("Invoice #" + invoice.getInvoiceId()
                    + " is not UNPAID (current: " + invoice.getStatus() + ")");
        }

        // ✅ Apply discount BEFORE calculating amount (sets usePointsSelected/usedPoints/finalAmount)
        invoice = invoiceDiscountService.apply(invoice.getInvoiceId(), usePoints);

        double amount = resolvePayAmount(invoice);
        String currency = (invoice.getCurrency() == null || invoice.getCurrency().isBlank()) ? "VND" : invoice.getCurrency();

        // ✅ UPSERT transaction by invoiceId
        Transaction incoming = Transaction.builder()
                .amount(amount)
                .currency(currency)
                .description("Thanh toán hóa đơn #" + invoice.getInvoiceId() + " qua VNPay")
                .status(TransactionStatus.PENDING)
                .driver(driver)
                .invoice(invoice)
                .paymentMethod(method)
                .build();

        Transaction tx = transactionService.addTransaction(incoming);

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
        String createDate = LocalDateTime.now(TENANT_ZONE).format(fmt);

        long vnpAmount = Math.max(0, Math.round(amount * 100));
        String txnRef = "TX" + tx.getTransactionId();

        Map<String, String> vnpParams = new TreeMap<>();
        vnpParams.put("vnp_Version", "2.1.0");
        vnpParams.put("vnp_Command", "pay");
        vnpParams.put("vnp_TmnCode", tmnCode);
        vnpParams.put("vnp_Locale", "vn");
        vnpParams.put("vnp_CurrCode", currency);
        vnpParams.put("vnp_TxnRef", txnRef);
        vnpParams.put("vnp_OrderInfo", "Thanh toan hoa don #" + invoice.getInvoiceId());
        vnpParams.put("vnp_OrderType", "other");
        vnpParams.put("vnp_Amount", String.valueOf(vnpAmount));
        vnpParams.put("vnp_ReturnUrl", returnUrl + "?invoiceId=" + invoice.getInvoiceId()
                + "&transactionId=" + tx.getTransactionId());
        vnpParams.put("vnp_CreateDate", createDate);
        vnpParams.put("vnp_IpAddr", clientIp);

        String signData = buildQuery(vnpParams, true);
        String secureHash = hmacSHA512(secretKey, signData);

        vnpParams.put("vnp_SecureHash", secureHash);
        String query = buildQuery(vnpParams, false);

        return vnpUrl + "?" + query;
    }

    private String buildQuery(Map<String, String> params, boolean isForSign) throws Exception {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (isForSign && "vnp_SecureHash".equals(e.getKey())) continue;
            builder.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8.toString()))
                    .append("=")
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8.toString()))
                    .append("&");
        }
        if (builder.length() > 0) builder.deleteCharAt(builder.length() - 1);
        return builder.toString();
    }

    private String hmacSHA512(String key, String data) throws NoSuchAlgorithmException, InvalidKeyException {
        String safeKey = key == null ? "" : key.trim();
        Mac hmac = Mac.getInstance("HmacSHA512");
        SecretKeySpec keySpec = new SecretKeySpec(safeKey.getBytes(StandardCharsets.UTF_8), "HmacSHA512");
        hmac.init(keySpec);
        byte[] bytes = hmac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    @Transactional
    public void handleVnPayReturn(HttpServletRequest request) throws Exception {
        this.secretKey = (this.secretKey == null) ? "" : this.secretKey.trim();

        String receivedHashParam = request.getParameter("vnp_SecureHash");
        String receivedHash = (receivedHashParam == null) ? "" : receivedHashParam.trim();

        // ==== RAW sign ====
        String rawQuery = Optional.ofNullable(request.getQueryString()).orElse("");
        Map<String, String> vnpRaw = new HashMap<>();
        for (String pair : rawQuery.split("&")) {
            int i = pair.indexOf('=');
            if (i <= 0) continue;
            String k = pair.substring(0, i);
            String v = pair.substring(i + 1);
            if (k.startsWith("vnp_")) vnpRaw.put(k, v);
        }
        String receivedHashRaw = Optional.ofNullable(vnpRaw.remove("vnp_SecureHash")).orElse(receivedHash);
        vnpRaw.remove("vnp_SecureHashType");
        SortedMap<String, String> rawSorted = new TreeMap<>(vnpRaw);
        StringBuilder rawDataToSign = new StringBuilder();
        for (Map.Entry<String, String> e : rawSorted.entrySet()) {
            if (rawDataToSign.length() > 0) rawDataToSign.append('&');
            rawDataToSign.append(e.getKey()).append('=').append(e.getValue());
        }
        String rawExpected = hmacSHA512(secretKey, rawDataToSign.toString());

        // ==== DEC sign ====
        Map<String, String[]> pm = request.getParameterMap();
        Map<String, String> vnpDecoded = new HashMap<>();
        pm.forEach((k, v) -> {
            if (k.startsWith("vnp_")) vnpDecoded.put(k, (v != null && v.length > 0) ? v[0] : "");
        });
        String receivedHashDec = Optional.ofNullable(vnpDecoded.remove("vnp_SecureHash")).orElse(receivedHash);
        vnpDecoded.remove("vnp_SecureHashType");
        SortedMap<String, String> decSorted = new TreeMap<>(vnpDecoded);
        StringBuilder decDataToSign = new StringBuilder();
        for (Map.Entry<String, String> e : decSorted.entrySet()) {
            if (decDataToSign.length() > 0) decDataToSign.append('&');
            decDataToSign.append(rfc3986(e.getKey())).append('=').append(rfc3986(e.getValue()));
        }
        String decExpected = hmacSHA512(secretKey, decDataToSign.toString());

        boolean ok =
                (rawExpected.equalsIgnoreCase(receivedHash) || rawExpected.equalsIgnoreCase(receivedHashRaw))
                        || (decExpected.equalsIgnoreCase(receivedHash) || decExpected.equalsIgnoreCase(receivedHashDec));

        if (!ok) throw new SecurityException("Invalid VNPay signature");

        Long invoiceId = Long.valueOf(request.getParameter("invoiceId"));
        Long transactionId = Long.valueOf(request.getParameter("transactionId"));

        Invoice invoice = invoiceService.findById(invoiceId)
                .orElseThrow(() -> new ErrorException("Invoice not found"));
        Transaction tx = transactionService.findById(transactionId)
                .orElseThrow(() -> new ErrorException("Transaction not found"));

        String responseCode = request.getParameter("vnp_ResponseCode");

        if ("00".equals(responseCode)) {
            // ✅ mark paid
            tx.setStatus(TransactionStatus.COMPLETED);
            invoice.setStatus(InvoiceStatus.PAID);
            invoice.setPaidAt(LocalDateTime.now(TENANT_ZONE));

            // ✅ upsert + save
            transactionService.addTransaction(tx);
            invoiceService.save(invoice);

            // ✅ ONLY ONE place handles loyalty (reset OR earn)
            loyaltyFinalizeService.finalizeOnPaymentSuccess(tx.getTransactionId(), invoice.getInvoiceId());

            // notify amount must show FINAL
            double paidAmount = resolvePayAmount(invoice);

            var driver = invoice.getDriver();
            var user = (driver != null) ? driver.getUser() : null;

            var session = invoice.getSession();
            var booking = (session != null) ? session.getBooking() : null;
            var station = (booking != null) ? booking.getStation() : null;

            String stationName = station != null ? station.getStationName() : "Trạm sạc";
            String title = "Thanh toán thành công hóa đơn #" + invoice.getInvoiceId();
            String content = "Số tiền: " + String.format("%,.0f", paidAmount) + " " + invoice.getCurrency()
                    + " | Trạm: " + stationName;

            Notification noti = new Notification();
            noti.setUser(user);
            noti.setTitle(title);
            noti.setContentNoti(content);
            noti.setType(NotificationTypes.PAYMENT_SUCCESS);
            noti.setStatus(Notification.STATUS_UNREAD);
            noti.setTransaction(tx);
            noti.setSession(invoice.getSession());
            noti.setCreatedAt(LocalDateTime.now(TENANT_ZONE));
            notificationsService.save(noti);
            eventPublisher.publishEvent(new NotificationCreatedEvent(noti.getNotiId()));

        } else {
            tx.setStatus(TransactionStatus.FAILED);
            if (invoice.getStatus() == InvoiceStatus.PENDING) {
                invoice.setStatus(InvoiceStatus.FAILED);
            }
            transactionService.addTransaction(tx);
            invoiceService.save(invoice);

            var driver = invoice.getDriver();
            var user = (driver != null) ? driver.getUser() : null;

            Notification noti = new Notification();
            noti.setUser(user);
            noti.setTitle("Thanh toán thất bại cho hóa đơn #" + invoice.getInvoiceId());
            noti.setContentNoti("Mã phản hồi VNPay: " + responseCode + ". Vui lòng thử lại.");
            noti.setType(NotificationTypes.PAYMENT_FAILED);
            noti.setStatus(Notification.STATUS_UNREAD);
            noti.setTransaction(tx);
            noti.setSession(invoice.getSession());
            noti.setCreatedAt(LocalDateTime.now(TENANT_ZONE));
            notificationsService.save(noti);
            eventPublisher.publishEvent(new NotificationCreatedEvent(noti.getNotiId()));
        }
    }

    private static String rfc3986(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%7E", "~");
    }

    @Transactional
    public String processEvmPayment(Long userId, Long sessionId, Long paymentMethodId, boolean usePoints) {

        Driver driver = driverService.findByUser_UserId(userId).orElse(null);

        ChargingSession session = chargingSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ErrorException("Session not found"));

        PaymentMethod method = paymentMethodService.findById(paymentMethodId)
                .orElseThrow(() -> new ErrorException("Payment method not found"));

        Invoice invoice = invoiceService.findBySession_SessionId(sessionId)
                .orElseThrow(() -> new ErrorException(
                        "No invoice found for session " + sessionId + ". Stop session must create an UNPAID invoice first."));

        if (invoice.getStatus() != InvoiceStatus.UNPAID) {
            throw new ConflictException("Invoice #" + invoice.getInvoiceId()
                    + " is not UNPAID (current: " + invoice.getStatus() + ")");
        }

        // ✅ apply discount first
        invoice = invoiceDiscountService.apply(invoice.getInvoiceId(), usePoints);

        double amount = resolvePayAmount(invoice);
        String currency = (invoice.getCurrency() == null || invoice.getCurrency().isBlank()) ? "VND" : invoice.getCurrency();

        // ✅ UPSERT
        Transaction incoming = Transaction.builder()
                .amount(amount)
                .currency(currency)
                .description("Thanh toán hóa đơn #" + invoice.getInvoiceId() + " qua EVM")
                .status(TransactionStatus.COMPLETED)
                .driver(driver)
                .invoice(invoice)
                .paymentMethod(method)
                .build();

        Transaction tx = transactionService.addTransaction(incoming);

        invoice.setStatus(InvoiceStatus.PAID);
        invoice.setPaidAt(LocalDateTime.now(TENANT_ZONE));
        invoiceService.save(invoice);

        // ✅ ONLY finalize here
        loyaltyFinalizeService.finalizeOnPaymentSuccess(tx.getTransactionId(), invoice.getInvoiceId());

        return "Payment successful (EVM)";
    }

    public boolean isVnPayMethod(Long paymentMethodId) {
        PaymentMethod method = paymentMethodService.findById(paymentMethodId)
                .orElseThrow(() -> new ErrorException("Payment method not found"));
        return method.getProvider() == PaymentProvider.VNPAY;
    }

    public boolean isEvmMethod(Long paymentMethodId) {
        PaymentMethod method = paymentMethodService.findById(paymentMethodId)
                .orElseThrow(() -> new ErrorException("Payment method not found"));
        return method.getProvider() == PaymentProvider.EVM || method.getMethodType() == PaymentType.CASH;
    }
}
