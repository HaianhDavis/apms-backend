package com.apms.domain.ai.service.provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in local config audit. Loads Boot config and dotenv without databases or scheduled jobs. */
@EnabledIfSystemProperty(named = "gemini.local.audit", matches = "true")
class GeminiLocalConfigurationAuditTest {
    @Test
    void resolvesLocalBootConfiguration() {
        try (var context = new SpringApplicationBuilder(GeminiApiKeyManager.class)
                .web(WebApplicationType.NONE)
                .run("--logging.level.com.apms.domain.ai.service.provider=DEBUG")) {
            var manager = context.getBean(GeminiApiKeyManager.class);
            assertThat(manager.keyCount()).isPositive();
            System.out.println("LOCAL AUDIT selected credential #" + (manager.activeKeyIndex() + 1)
                    + ": " + GeminiCredentialDiagnostics.describeKey(manager.getApiKey()));
            if (Boolean.getBoolean("gemini.local.http")) {
                var factory = new org.springframework.http.client.JdkClientHttpRequestFactory();
                factory.setReadTimeout(java.time.Duration.ofSeconds(30));
                var client = org.springframework.web.client.RestClient.builder().requestFactory(factory)
                        .requestInterceptor(GeminiCredentialDiagnostics.interceptor("LocalAuditProbe")).build();
                String model = context.getEnvironment().getProperty("app.ai.gemini.model");
                var executor = new GeminiRequestExecutor(manager, 0);
                try {
                    executor.execute(key -> client.post()
                            .uri("https://generativelanguage.googleapis.com/v1/models/{model}:generateContent?key={key}", model, key)
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .body(java.util.Map.of("contents", java.util.List.of(java.util.Map.of("parts",
                                    java.util.List.of(java.util.Map.of("text", "Reply OK")))),
                                    "generationConfig", java.util.Map.of("maxOutputTokens", 16)))
                            .retrieve().toBodilessEntity());
                    System.out.println("LOCAL AUDIT HTTP probe succeeded (not a document extraction).");
                } catch (RuntimeException exception) {
                    // Raw HTTP exception messages can contain the credential-bearing URI.
                    System.out.println("LOCAL AUDIT HTTP probe failed; see sanitized HTTP status logs. Exception type="
                            + exception.getClass().getSimpleName());
                }
            }
        }
    }
}
