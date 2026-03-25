package com.swp391.gr3.ev_management.service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import com.swp391.gr3.ev_management.entity.OtpVerification;
import com.swp391.gr3.ev_management.repository.OtpRepository;

import jakarta.annotation.PostConstruct;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class OtpServiceImpl implements OtpService {

    private static final String DEFAULT_FROM = "no-reply@evcsystem.online";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OtpRepository otpRepository;
    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;

    @Value("${app.notifications.email.from:no-reply@evcsystem.online}")
    private String from;

    @Value("${app.notifications.email.enabled:true}")
    private boolean emailEnabled;

    @PostConstruct
    void normalizeMailConfig() {
        if (!StringUtils.hasText(from)) {
            from = DEFAULT_FROM;
        }
    }

    @Override
    public String generateOtp(String email) {
        String otp = String.format("%06d", RANDOM.nextInt(1_000_000));
        LocalDateTime now = LocalDateTime.now();

        otpRepository.save(OtpVerification.builder()
                .email(email)
                .otpCode(otp)
                .createdAt(now)
                .expiresAt(now.plusMinutes(5))
                .verified(false)
                .build());

        if (!emailEnabled) {
            log.warn("Email sending disabled type=otp to={}", email);
            return otp;
        }

        try {
            Context context = new Context();
            context.setVariable("otp", otp);

            String htmlBody = templateEngine.process("email-otp", context);

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");

            helper.setFrom(from);
            helper.setTo(email);
            helper.setSubject("🔐 [EV Management] Mã xác thực OTP");
            helper.setText(htmlBody, true);

            log.info("Sending email type=otp from={} to={}", from, email);
            mailSender.send(message);
            log.info("Email sent type=otp to={}", email);

        } catch (Exception e) {
            log.error("Email send failed type=otp to={}", email, e);
        }

        return otp;
    }

    @Override
    public boolean verifyOtp(String email, String otpCode) {
        Optional<OtpVerification> latestOtp = otpRepository.findTopByEmailOrderByCreatedAtDesc(email);
        if (latestOtp.isEmpty()) return false;

        OtpVerification otp = latestOtp.get();

        if (otp.isVerified()) return false;
        if (otp.getExpiresAt().isBefore(LocalDateTime.now())) return false;
        if (!otp.getOtpCode().equals(otpCode)) return false;

        otp.setVerified(true);
        otpRepository.save(otp);
        return true;
    }

    @Override
    public Optional<OtpVerification> findTopByEmailOrderByCreatedAtDesc(String email) {
        return otpRepository.findTopByEmailOrderByCreatedAtDesc(email);
    }

    @Override
    public void save(OtpVerification latest) {
        otpRepository.save(latest);
    }
}
