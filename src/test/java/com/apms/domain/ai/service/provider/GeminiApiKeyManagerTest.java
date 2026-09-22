package com.apms.domain.ai.service.provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class GeminiApiKeyManagerTest {
    @Test
    void acceptsBothPrefixesAndMixedCredentials() {
        for (String keys : new String[]{"AIzaAAA,AIzaBBB", "AQ111,AQ222", "AIzaAAA,AQ222"}) {
            var manager = new GeminiApiKeyManager(keys, "ignored-legacy");
            assertThat(manager.keyCount()).isEqualTo(2);
            assertThat(manager.getCurrentKey().orElseThrow().value()).isEqualTo(keys.split(",")[0]);
            assertThat(manager.getApiKey()).isEqualTo(keys.split(",")[0]);
        }
    }

    @Test
    void stripsQuotesAndWhitespaceAndIgnoresEmptyEntries() {
        assertThat(GeminiApiKeyManager.parseKeys(" \"AIzaAAA\", 'AIzaBBB', `AQ222`, ,"))
                .containsExactly("AIzaAAA", "AIzaBBB", "AQ222");
    }

    @Test
    void warnsAboutWrongDelimiterWithoutPrintingCredentials(CapturedOutput output) {
        assertThat(GeminiApiKeyManager.parseKeys("AIzaAAA;AIzaBBB")).hasSize(1);
        assertThat(output.getAll()).contains("only commas separate credentials")
                .doesNotContain("AIzaAAA", "AIzaBBB");
    }

    @Test
    void fallsBackToLegacyWhenConfiguredListContainsOnlyDummy() {
        assertThat(new GeminiApiKeyManager("dummy-key", "AQlegacy").getApiKey()).isEqualTo("AQlegacy");
    }

    @Test
    void metadataNeverDisclosesWholeCredentialOrControlCharacters() {
        assertThat(GeminiCredentialDiagnostics.describeKey("AQ.A-secret-value"))
                .isEqualTo("prefix=AQ.A, length=17");
        assertThat(GeminiCredentialDiagnostics.describeKey("abc")).doesNotContain("abc");
        assertThat(GeminiCredentialDiagnostics.describeKey("A\nBCsecret")).doesNotContain("\n", "secret");
    }
}
