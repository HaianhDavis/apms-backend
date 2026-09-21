package com.apms.domain.ai.service.provider;

import com.apms.common.exception.BusinessValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(OutputCaptureExtension.class)
class GeminiRequestExecutorTest {

    @Test
    void singleLegacyKeyContinuesWorking() {
        GeminiApiKeyManager manager = new GeminiApiKeyManager("", "legacy-key");
        GeminiRequestExecutor executor = new GeminiRequestExecutor(manager, 1);
        List<String> usedKeys = new ArrayList<>();

        String result = executor.execute(apiKey -> {
            usedKeys.add(apiKey);
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(usedKeys).containsExactly("legacy-key");
        assertThat(manager.activeKeyIndex()).isZero();
    }

    @Test
    void multipleKeysUseFirstKeyAndKeepItActiveWhenItSucceeds() {
        GeminiApiKeyManager manager = new GeminiApiKeyManager("key1,key2", "legacy-key");
        GeminiRequestExecutor executor = new GeminiRequestExecutor(manager, 1);
        List<String> usedKeys = new ArrayList<>();

        String result = executor.execute(apiKey -> {
            usedKeys.add(apiKey);
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(usedKeys).containsExactly("key1");
        assertThat(manager.activeKeyIndex()).isZero();
    }

    @Test
    void keyReturning429FallsBackToNextKey() {
        GeminiApiKeyManager manager = new GeminiApiKeyManager("key1,key2", "");
        GeminiRequestExecutor executor = new GeminiRequestExecutor(manager, 1);
        List<String> usedKeys = new ArrayList<>();

        String result = executor.execute(apiKey -> {
            usedKeys.add(apiKey);
            if ("key1".equals(apiKey)) {
                throw responseStatus(429);
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(usedKeys).containsExactly("key1", "key2");
        assertThat(manager.activeKeyIndex()).isEqualTo(1);
    }

    @Test
    void successfulFallbackKeyBecomesActiveForSubsequentRequests() {
        GeminiApiKeyManager manager = new GeminiApiKeyManager("key1,key2", "");
        GeminiRequestExecutor executor = new GeminiRequestExecutor(manager, 1);

        executor.execute(apiKey -> {
            if ("key1".equals(apiKey)) {
                throw responseStatus(429);
            }
            return "first";
        });

        List<String> secondRequestKeys = new ArrayList<>();
        String result = executor.execute(apiKey -> {
            secondRequestKeys.add(apiKey);
            return "second";
        });

        assertThat(result).isEqualTo("second");
        assertThat(secondRequestKeys).containsExactly("key2");
        assertThat(manager.activeKeyIndex()).isEqualTo(1);
    }

    @Test
    void sequentialFailuresContinueUntilAvailableKeySucceeds() {
        GeminiApiKeyManager manager = new GeminiApiKeyManager("key1,key2,key3", "");
        GeminiRequestExecutor executor = new GeminiRequestExecutor(manager, 1);
        List<String> usedKeys = new ArrayList<>();

        String result = executor.execute(apiKey -> {
            usedKeys.add(apiKey);
            if (!"key3".equals(apiKey)) {
                throw responseStatus(429);
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(usedKeys).containsExactly("key1", "key2", "key3");
        assertThat(manager.activeKeyIndex()).isEqualTo(2);
    }

    @Test
    void unauthorizedKeyFallsBackToNextKey() {
        GeminiApiKeyManager manager = new GeminiApiKeyManager("key1,key2", "");
        GeminiRequestExecutor executor = new GeminiRequestExecutor(manager, 1);

        String result = executor.execute(apiKey -> {
            if ("key1".equals(apiKey)) {
                throw responseStatus(401);
            }
            return apiKey;
        });

        assertThat(result).isEqualTo("key2");
        assertThat(manager.activeKeyIndex()).isEqualTo(1);
    }

    @Test
    void forbiddenKeyFallsBackToNextKey() {
        GeminiApiKeyManager manager = new GeminiApiKeyManager("key1,key2", "");
        GeminiRequestExecutor executor = new GeminiRequestExecutor(manager, 1);

        String result = executor.execute(apiKey -> {
            if ("key1".equals(apiKey)) {
                throw responseStatus(403);
            }
            return apiKey;
        });

        assertThat(result).isEqualTo("key2");
        assertThat(manager.activeKeyIndex()).isEqualTo(1);
    }

    @Test
    void badRequestDoesNotSwitchKey() {
        GeminiApiKeyManager manager = new GeminiApiKeyManager("key1,key2", "");
        GeminiRequestExecutor executor = new GeminiRequestExecutor(manager, 1);
        List<String> usedKeys = new ArrayList<>();

        BusinessValidationException exception = assertThrows(BusinessValidationException.class, () ->
                executor.execute(apiKey -> {
                    usedKeys.add(apiKey);
                    throw responseStatus(400);
                }));

        assertThat(exception.getMessage()).isEqualTo("GEMINI_INVALID_REQUEST");
        assertThat(usedKeys).containsExactly("key1");
        assertThat(manager.activeKeyIndex()).isZero();
        assertThat(manager.isUnavailable(0)).isFalse();
    }

    @Test
    void transientProviderErrorRetriesCurrentKeyThenSwitches() {
        GeminiApiKeyManager manager = new GeminiApiKeyManager("key1,key2", "");
        GeminiRequestExecutor executor = new GeminiRequestExecutor(manager, 1);
        List<String> usedKeys = new ArrayList<>();
        AtomicInteger key1Calls = new AtomicInteger();

        String result = executor.execute(apiKey -> {
            usedKeys.add(apiKey);
            if ("key1".equals(apiKey) && key1Calls.incrementAndGet() <= 2) {
                throw responseStatus(503);
            }
            return apiKey;
        });

        assertThat(result).isEqualTo("key2");
        assertThat(usedKeys).containsExactly("key1", "key1", "key2");
        assertThat(manager.activeKeyIndex()).isEqualTo(1);
    }

    @Test
    void allConfiguredKeysFailWithControlledException() {
        GeminiApiKeyManager manager = new GeminiApiKeyManager("key1,key2", "");
        GeminiRequestExecutor executor = new GeminiRequestExecutor(manager, 1);
        List<String> usedKeys = new ArrayList<>();

        GeminiAllKeysUnavailableException exception = assertThrows(GeminiAllKeysUnavailableException.class, () ->
                executor.execute(apiKey -> {
                    usedKeys.add(apiKey);
                    throw responseStatus(429);
                }));

        assertThat(exception.getErrorCode()).isEqualTo("GEMINI_SERVICE_UNAVAILABLE");
        assertThat(exception.getMessage()).isEqualTo("AI service is temporarily unavailable. Please try again later.");
        assertThat(usedKeys).containsExactly("key1", "key2");
    }

    @Test
    void concurrentFailoverDoesNotSkipTheNextKey() throws Exception {
        GeminiApiKeyManager manager = new GeminiApiKeyManager("key1,key2,key3", "");
        GeminiRequestExecutor executor = new GeminiRequestExecutor(manager, 1);
        CyclicBarrier key1Barrier = new CyclicBarrier(2);
        AtomicInteger key1Attempts = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            List<Future<String>> futures = List.of(
                    pool.submit(() -> concurrent429ThenSuccessOnKey2(executor, key1Barrier, key1Attempts)),
                    pool.submit(() -> concurrent429ThenSuccessOnKey2(executor, key1Barrier, key1Attempts))
            );

            assertThat(futures.get(0).get(5, TimeUnit.SECONDS)).isEqualTo("key2");
            assertThat(futures.get(1).get(5, TimeUnit.SECONDS)).isEqualTo("key2");
            assertThat(key1Attempts).hasValue(2);
            assertThat(manager.activeKeyIndex()).isEqualTo(1);
            assertThat(manager.isUnavailable(2)).isFalse();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void keysAreNotPresentInLogsOrControlledException(CapturedOutput output) {
        String firstSecret = "secret-key-one";
        String secondSecret = "secret-key-two";
        GeminiApiKeyManager manager = new GeminiApiKeyManager(firstSecret + "," + secondSecret, "");
        GeminiRequestExecutor executor = new GeminiRequestExecutor(manager, 1);

        GeminiAllKeysUnavailableException exception = assertThrows(GeminiAllKeysUnavailableException.class, () ->
                executor.execute(apiKey -> {
                    throw responseStatus(401);
                }));

        assertThat(exception.getMessage()).doesNotContain(firstSecret, secondSecret);
        assertThat(output).doesNotContain(firstSecret, secondSecret);
    }

    @Test
    void configuredApiKeysTrimWhitespaceAndIgnoreBlanks() {
        GeminiApiKeyManager manager = new GeminiApiKeyManager(" key1, , key2 ,, key3 ", "legacy-key");
        GeminiRequestExecutor executor = new GeminiRequestExecutor(manager, 1);
        List<String> usedKeys = new CopyOnWriteArrayList<>();

        executor.execute(apiKey -> {
            usedKeys.add(apiKey);
            return "ok";
        });

        assertThat(manager.keyCount()).isEqualTo(3);
        assertThat(usedKeys).containsExactly("key1");
    }

    private String concurrent429ThenSuccessOnKey2(GeminiRequestExecutor executor,
                                                  CyclicBarrier key1Barrier,
                                                  AtomicInteger key1Attempts) {
        return executor.execute(apiKey -> {
            if ("key1".equals(apiKey)) {
                key1Attempts.incrementAndGet();
                await(key1Barrier);
                throw responseStatus(429);
            }
            return apiKey;
        });
    }

    private void await(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private RestClientResponseException responseStatus(int statusCode) {
        HttpStatus status = HttpStatus.valueOf(statusCode);
        return new RestClientResponseException(
                "Gemini status " + statusCode,
                statusCode,
                status.getReasonPhrase(),
                HttpHeaders.EMPTY,
                "provider error".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8
        );
    }
}
