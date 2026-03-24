package com.swp391.gr3.ev_management.service;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailServiceImpl implements EmailService {

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;

    @Value("${app.notifications.email.from:no-reply@evcsystem.online}")
    private String from;

    @Async("mailExecutor")
    @Override
    public void sendNotificationEmailTpl(String to,
                                         String subject,
                                         String displayName,
                                         Object title,
                                         Object body,
                                         Object type,
                                         Object status,
                                         Object createdAt) {
        try {
            Context ctx = new Context();
            ctx.setVariable("displayName", safe(displayName));
            ctx.setVariable("title", safe(title));
            ctx.setVariable("body", safe(body));
            ctx.setVariable("type", safe(type));
            ctx.setVariable("status", safe(status));
            ctx.setVariable("createdAt", safe(createdAt));

            String html = templateEngine.process("email-notification", ctx);

            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, false, "UTF-8");

            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);

            mailSender.send(msg);
            log.info("Notification email sent successfully to {}", to);
        } catch (Exception e) {
            log.error("sendNotificationEmailTpl failed for recipient={}", to, e);
        }
    }

    @Async("mailExecutor")
    @Override
    public void sendBookingCancelledTpl(String to,
                                        String subject,
                                        String displayName,
                                        Long bookingId,
                                        String stationName,
                                        String timeRange) {
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");

            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);

            Context ctx = new Context();
            ctx.setVariable("displayName", safe(displayName));
            ctx.setVariable("bookingId", bookingId);
            ctx.setVariable("stationName", safe(stationName));
            ctx.setVariable("timeRange", safe(timeRange));

            String html = templateEngine.process("booking-cancelled", ctx);
            helper.setText(html, true);

            mailSender.send(mime);
            log.info("Booking-cancelled email sent successfully to {}, bookingId={}", to, bookingId);
        } catch (Exception e) {
            log.error("Failed to send booking-cancelled email to {}, bookingId={}", to, bookingId, e);
        }
    }

    @Async("mailExecutor")
    @Override
    public void sendBookingConfirmedTpl(String to,
                                        String subject,
                                        String displayName,
                                        Long bookingId,
                                        String station,
                                        String timeRange,
                                        String slotName,
                                        String connectorType,
                                        byte[] qrBytes) {
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");

            Context ctx = new Context();
            ctx.setVariable("displayName", safe(displayName));
            ctx.setVariable("bookingId", bookingId);
            ctx.setVariable("station", safe(station));
            ctx.setVariable("timeRange", safe(timeRange));
            ctx.setVariable("slotName", safe(slotName));
            ctx.setVariable("connectorType", safe(connectorType));
            ctx.setVariable("cid", "qr");

            String html = templateEngine.process("booking-confirmed", ctx);

            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);

            if (qrBytes != null && qrBytes.length > 0) {
                helper.addInline("qr", new ByteArrayResource(qrBytes), "image/png");
            }

            mailSender.send(msg);
            log.info("Booking-confirmed email sent successfully to {}, bookingId={}", to, bookingId);
        } catch (Exception e) {
            log.error("Failed to send booking-confirmed email to {}, bookingId={}", to, bookingId, e);
        }
    }

    @Async("mailExecutor")
    @Override
    public void sendPasswordEmailHtml(String to, String password) {
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, false, "UTF-8");

            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject("EV Station – Mật khẩu đăng nhập lần đầu");

            Context ctx = new Context();
            ctx.setVariable("password", safe(password));

            String html = templateEngine.process("password-first-login", ctx);
            helper.setText(html, true);

            mailSender.send(msg);
            log.info("Password email sent successfully to {}", to);
        } catch (Exception e) {
            log.error("Failed to send HTML password email to {}", to, e);
        }
    }

    private static String safe(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}