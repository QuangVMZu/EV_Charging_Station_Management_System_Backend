package com.swp391.gr3.ev_management.controller;

import com.swp391.gr3.ev_management.service.PaymentService;
import com.swp391.gr3.ev_management.service.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.Map;

@RestController
@RequestMapping("/api/payment/vnpay")
@RequiredArgsConstructor
public class VnPayController {

    private final PaymentService paymentService;
    private final TokenService tokenService;

    @Value("${app.frontend.vnpay-success-url}")
    private String vnpaySuccessUrl;

    @Value("${app.frontend.vnpay-fail-url}")
    private String vnpayFailUrl;

    @PostMapping("/create")
    public ResponseEntity<Map<String, Object>> create(
            @RequestParam Long sessionId,
            @RequestParam Long paymentMethodId,
            @RequestParam(defaultValue = "false") boolean usePoints,
            HttpServletRequest request
    ) throws Exception {

        Long userId = tokenService.extractUserIdFromRequest(request);

        if (paymentService.isEvmMethod(paymentMethodId)) {
            String msg = paymentService.processEvmPayment(userId, sessionId, paymentMethodId, usePoints);
            return ResponseEntity.ok(Collections.singletonMap("message", msg));
        }

        if (paymentService.isVnPayMethod(paymentMethodId)) {
            String clientIp = getClientIp(request);
            String payUrl = paymentService.createVnPayPaymentUrl(userId, sessionId, paymentMethodId, clientIp, usePoints);
            return ResponseEntity.ok(Collections.singletonMap("paymentUrl", payUrl));
        }

        return ResponseEntity.badRequest()
                .body(Collections.singletonMap("message", "Unsupported payment method"));
    }

    @GetMapping("/return")
    public ResponseEntity<Void> handleReturn(HttpServletRequest req) {
        String redirectUrl;
        try {
            paymentService.handleVnPayReturn(req);
            redirectUrl = vnpaySuccessUrl;
        } catch (Exception e) {
            redirectUrl = vnpayFailUrl;
        }

        return ResponseEntity.status(302)
                .header(HttpHeaders.LOCATION, redirectUrl)
                .build();
    }

    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}
