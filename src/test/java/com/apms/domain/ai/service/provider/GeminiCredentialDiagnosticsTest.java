package com.apms.domain.ai.service.provider;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

@ExtendWith(OutputCaptureExtension.class)
class GeminiCredentialDiagnosticsTest {
    @Test
    void recordsActualRequestMetadataAndStatusWithoutBodyOrSecret(CapturedOutput output) {
        Logger logger = (Logger) LoggerFactory.getLogger(GeminiCredentialDiagnostics.class);
        Level previous = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        try {
            String key = "AQ.A-test-secret";
            var manager = new GeminiApiKeyManager(key, "ignored");
            var executor = new GeminiRequestExecutor(manager, 0);
            var builder = RestClient.builder().requestInterceptor(GeminiCredentialDiagnostics.interceptor("test"));
            var server = MockRestServiceServer.bindTo(builder).build();
            String url = "https://generativelanguage.googleapis.com/v1/models/test:generateContent?key=" + key;
            server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON).body("sensitive response " + key));
            var client = builder.build();
            assertThrows(RuntimeException.class, () -> executor.execute(value -> client.post()
                    .uri("https://generativelanguage.googleapis.com/v1/models/test:generateContent?key={key}", value)
                    .retrieve().body(String.class)));
            server.verify();
            assertThat(output.getAll()).contains("prefix=AQ.A", "status=400")
                    .doesNotContain(key, "sensitive response");
        } finally {
            logger.setLevel(previous);
        }
    }
}
