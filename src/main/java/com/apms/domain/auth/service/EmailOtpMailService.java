package com.apms.domain.auth.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;

@Slf4j
@Service
public class EmailOtpMailService {
    private final JavaMailSender mailSender;
    private final String smtpHost;
    private final String smtpPort;
    private final String smtpUsername;
    private final String from;
    private final String fromName;
    private final boolean devFallback;

    public EmailOtpMailService(JavaMailSender mailSender,
                               @Value("${spring.mail.host:}") String smtpHost,
                               @Value("${spring.mail.port:587}") String smtpPort,
                               @Value("${spring.mail.username:}") String smtpUsername,
                               @Value("${app.mail.from:}") String from,
                               @Value("${app.mail.from-name:System APMS}") String fromName,
                               @Value("${app.mail.dev-fallback:true}") boolean devFallback) {
        this.mailSender = mailSender;
        this.smtpHost = smtpHost;
        this.smtpPort = smtpPort;
        this.smtpUsername = smtpUsername;
        this.from = from;
        this.fromName = fromName;
        this.devFallback = devFallback;
    }

    public EmailDeliveryResult sendVerificationCode(String recipient, String name, String otp) {
        if (smtpHost == null || smtpHost.isBlank()) {
            if (devFallback) {
                log.warn("SMTP is not configured (spring.mail.host is empty). No email sent; verification code for {} is: {} (dev fallback)", maskEmail(recipient), otp);
                return EmailDeliveryResult.devFallback();
            }
            log.warn("SMTP is not configured (spring.mail.host is empty) and dev fallback is disabled. No email sent to {}", maskEmail(recipient));
            return EmailDeliveryResult.failed("Email could not be sent because SMTP is not configured");
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(buildFromAddress(recipient));
            helper.setTo(recipient);
            helper.setSubject("Xác nhận tài khoản APMS");
            helper.setText("Xác nhận tài khoản\n\n"
                    + "Xin chào " + (name == null || name.isBlank() ? "bạn" : name) + ",\n\n"
                    + "Tài khoản của bạn đã được tạo bởi quản trị viên.\n\n"
                    + "Mã xác nhận Email của bạn là:\n\n" + otp + "\n\n"
                    + "Mã có hiệu lực trong 5 phút.\n\n"
                    + "Vui lòng không chia sẻ mã này với người khác.", false);
            mailSender.send(message);
            log.info("SMTP_SUCCESS email verification code delivered to {}", maskEmail(recipient));
            return EmailDeliveryResult.smtpSuccess();
        } catch (MessagingException | MailException ex) {
            log.error("SMTP_FAILURE email verification code not sent. host={}, port={}, username={}, to={}, cause={}, detail={}",
                    smtpHost, smtpPort, mask(smtpUsername), maskEmail(recipient), ex.getClass().getName(), sanitizeDetail(ex, otp));
            return EmailDeliveryResult.failed("Email could not be sent");
        }
    }

    private String resolveFrom(String recipient) {
        if (from != null && !from.isBlank()) return from;
        if (smtpUsername != null && !smtpUsername.isBlank()) return smtpUsername;
        return recipient;
    }

    private InternetAddress buildFromAddress(String recipient) throws MessagingException {
        try {
            InternetAddress address = new InternetAddress(resolveFrom(recipient));
            if (fromName != null && !fromName.isBlank()) {
                address.setPersonal(fromName, "UTF-8");
            }
            return address;
        } catch (UnsupportedEncodingException ex) {
            log.warn("Could not encode from display name '{}', falling back to address only", fromName);
            return new InternetAddress(resolveFrom(recipient));
        }
    }

    private String sanitizeDetail(Throwable ex, String otp) {
        String detail = ex.getMessage() == null ? "" : ex.getMessage();
        if (otp != null && !otp.isBlank()) detail = detail.replace(otp, "[REDACTED]");
        return detail;
    }

    private String mask(String value) {
        if (value == null || value.isBlank()) return "(not set)";
        return value.length() <= 2 ? "***" : value.substring(0, 2) + "***";
    }

    private String maskEmail(String value) {
        if (value == null || value.isBlank()) return "(not set)";
        int at = value.indexOf('@');
        if (at <= 1) return "***" + (at >= 0 ? value.substring(at) : "");
        return value.charAt(0) + "***" + value.substring(at);
    }
}
