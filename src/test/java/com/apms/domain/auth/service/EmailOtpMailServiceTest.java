package com.apms.domain.auth.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailOtpMailServiceTest {
    @Mock JavaMailSender mailSender;

    private ListAppender<ILoggingEvent> attachLogCapture() {
        Logger logger = (Logger) LoggerFactory.getLogger(EmailOtpMailService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private String joinedLogs(ListAppender<ILoggingEvent> logs) {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a, b) -> a + "\n" + b);
    }

    @Test
    void messageContainsRecipientSubjectOtpAndExpiryWithoutPassword() throws Exception {
        MimeMessage message = new MimeMessage((jakarta.mail.Session) null);
        when(mailSender.createMimeMessage()).thenReturn(message);
        EmailOtpMailService service = new EmailOtpMailService(mailSender, "smtp.test.com", "587", "no-reply@apms.com", "no-reply@apms.com", "System APMS", true);

        EmailDeliveryResult result = service.sendVerificationCode("user@example.com", "User", "123456");

        assertThat(result.delivered()).isTrue();
        assertThat(result.status()).isEqualTo("SMTP_SUCCESS");
        verify(mailSender).send(message);
        assertThat(message.getRecipients(MimeMessage.RecipientType.TO)[0].toString()).isEqualTo("user@example.com");
        assertThat(message.getSubject()).contains("Xác nhận");
        assertThat(message.getContent().toString()).contains("123456", "5 phút").doesNotContain("password");
    }

    @Test
    void fromHeaderContainsDisplayName() throws Exception {
        MimeMessage message = new MimeMessage((jakarta.mail.Session) null);
        when(mailSender.createMimeMessage()).thenReturn(message);
        EmailOtpMailService service = new EmailOtpMailService(mailSender, "smtp.test.com", "587", "no-reply@apms.com", "no-reply@apms.com", "System APMS", true);

        service.sendVerificationCode("user@example.com", "User", "123456");

        InternetAddress from = (InternetAddress) message.getFrom()[0];
        assertThat(from.getAddress()).isEqualTo("no-reply@apms.com");
        assertThat(from.getPersonal()).isEqualTo("System APMS");
    }

    @Test
    void smtpFailureIsReturnedWithoutThrowingAndLogsDoNotContainOtp() {
        MimeMessage message = new MimeMessage((jakarta.mail.Session) null);
        when(mailSender.createMimeMessage()).thenReturn(message);
        doThrow(new MailSendException("Failed messages: javax.mail.AuthenticationFailedException; message exceptions (1)"))
                .when(mailSender).send(message);
        EmailOtpMailService service = new EmailOtpMailService(mailSender, "smtp.test.com", "587", "user@fpt.works", "no-reply@apms.com", "System APMS", true);
        ListAppender<ILoggingEvent> logs = attachLogCapture();

        EmailDeliveryResult result = service.sendVerificationCode("user@example.com", "User", "123456");

        assertThat(result.delivered()).isFalse();
        assertThat(result.status()).isEqualTo("SMTP_FAILURE");
        assertThat(result.reason()).isNotBlank();
        String joined = joinedLogs(logs);
        assertThat(joined)
                .contains("SMTP_FAILURE", "smtp.test.com", "587", "username=us***", "u***@example.com")
                .doesNotContain("123456", "password");
    }

    @Test
    void devFallbackLogsOtpWhenSmtpMissing() {
        EmailOtpMailService service = new EmailOtpMailService(mailSender, "", "587", "", "", "System APMS", true);
        ListAppender<ILoggingEvent> logs = attachLogCapture();

        EmailDeliveryResult result = service.sendVerificationCode("user@example.com", "User", "123456");

        assertThat(result.delivered()).isFalse();
        assertThat(result.status()).isEqualTo("DEV_FALLBACK");
        verifyNoInteractions(mailSender);
        assertThat(joinedLogs(logs)).contains("123456");
    }

    @Test
    void fallbackDisabledWithoutSmtpReturnsFailureWithoutLoggingOtp() {
        EmailOtpMailService service = new EmailOtpMailService(mailSender, "", "587", "", "", "System APMS", false);
        ListAppender<ILoggingEvent> logs = attachLogCapture();

        EmailDeliveryResult result = service.sendVerificationCode("user@example.com", "User", "123456");

        assertThat(result.delivered()).isFalse();
        assertThat(result.status()).isEqualTo("SMTP_FAILURE");
        verifyNoInteractions(mailSender);
        assertThat(joinedLogs(logs)).doesNotContain("123456");
    }

    @Test
    void fallsBackToRecipientAsFromWhenConfiguredFromAndUsernameAreBlank() throws Exception {
        MimeMessage message = new MimeMessage((jakarta.mail.Session) null);
        when(mailSender.createMimeMessage()).thenReturn(message);
        EmailOtpMailService service = new EmailOtpMailService(mailSender, "smtp.test.com", "587", "", "", "System APMS", true);

        EmailDeliveryResult result = service.sendVerificationCode("user@example.com", "User", "123456");

        assertThat(result.delivered()).isTrue();
        assertThat(result.status()).isEqualTo("SMTP_SUCCESS");
        InternetAddress from = (InternetAddress) message.getFrom()[0];
        assertThat(from.getAddress()).isEqualTo("user@example.com");
        assertThat(from.getPersonal()).isEqualTo("System APMS");
    }
}
