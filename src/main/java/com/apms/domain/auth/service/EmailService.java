package com.apms.domain.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;

    public void sendPasswordResetEmail(String toEmail, String resetUrl) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(toEmail);
            message.setSubject("APMS - Khôi phục mật khẩu");
            message.setText("Xin chào,\n\nBạn đã yêu cầu khôi phục mật khẩu cho tài khoản APMS của mình.\n\n" +
                    "Vui lòng truy cập đường dẫn sau để đặt lại mật khẩu mới. Đường dẫn này sẽ hết hạn sau 15 phút:\n" +
                    resetUrl + "\n\n" +
                    "Nếu bạn không yêu cầu khôi phục mật khẩu, vui lòng bỏ qua email này.\n\n" +
                    "Trân trọng,\nAPMS Team");
            
            mailSender.send(message);
            log.info("Password reset email sent to {}", toEmail);
        } catch (Exception e) {
            log.error("Failed to send password reset email to {}: {}", toEmail, e.getMessage());
            // Optionally, handle the error gracefully without throwing exception, 
            // especially in development with dummy mail sender.
        }
    }
}
