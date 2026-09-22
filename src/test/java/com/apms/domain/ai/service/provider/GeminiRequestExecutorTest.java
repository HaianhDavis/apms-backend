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
        GeminiRequestExecutor executor = new GeminiRequestExecutor(manager, 1, 2);
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

    // ==================== TEST 2: 503 retry succeeds on same key ====================
    @Test
    void transient503RetriesOnSameKeyAndSucceeds() {
        GeminiApiKeyManager manager = new GeminiApiKeyManager("key1,key2", "");
        GeminiRequestExecutor executor = new GeminiRequestExecutor(manager, 2);
        List<String> usedKeys = new ArrayList<>();
        AtomicInteger callCount = new AtomicInteger();

        String result = executor.execute(apiKey -> {
            usedKeys.add(apiKey);
            if ("key1".equals(apiKey) && callCount.incrementAndGet() == 1) {
                throw responseStatus(503);
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(usedKeys).containsExactly("key1", "key1");
        assertThat(manager.activeKeyIndex()).isZero();
    }

    // ==================== TEST 3: 503 -> 503 -> 200 bounded retries succeed ====================
    @Test
    void bounded503RetriesSucceedWithoutAllKeysException() {
        GeminiApiKeyManager manager = new GeminiApiKeyManager("key1", "");
        GeminiRequestExecutor executor = new GeminiRequestExecutor(manager, 2);
        AtomicInteger callCount = new AtomicInteger();

        String result = executor.execute(apiKey -> {
            if (callCount.incrementAndGet() <= 2) {
                throw responseStatus(503);
            }
            return "success";
        });

        assertThat(result).isEqualTo("success");
        assertThat(callCount).hasValue(3);
    }

    // ==================== TEST 4: 503 exhausted on key#1 -> failover to key#2 ====================
    @Test
    void repeated503OnKey1FailsOverToKey2() {
        GeminiApiKeyManager manager = new GeminiApiKeyManager("key1,key2", "");
        // transientAttempts=1 so key1 gets 1 initial + 1 retry = 2 attempts of 503
        GeminiRequestExecutor executor = new GeminiRequestExecutor(manager, 1);
        List<String> usedKeys = new ArrayList<>();

        String result = executor.execute(apiKey -> {
            usedKeys.add(apiKey);
            if ("key1".equals(apiKey)) {
                throw responseStatus(503);
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(usedKeys).containsExactly("key1", "key1", "key2");
        assertThat(manager.activeKeyIndex()).isEqualTo(1);
    }

    // ==================== TEST 5: 503 does NOT permanently disable key ====================
    @Test
    void key503DoesNotPermanentlyDisableKeyInManager() {
        GeminiApiKeyManager manager = new GeminiApiKeyManager("key1,key2", "");
        GeminiRequestExecutor executor = new GeminiRequestExecutor(manager, 1);
        List<String> usedKeys = new ArrayList<>();

        // First call: key1 503 -> failover to key2
        executor.execute(apiKey -> {
            usedKeys.add(apiKey);
            if ("key1".equals(apiKey)) {
                throw responseStatus(503);
            }
            return "first-ok";
        });

        // key1 must NOT be permanently unavailable after 503
        assertThat(manager.isUnavailable(0)).isFalse();
        assertThat(manager.activeKeyIndex()).isEqualTo(1);
    }

    // ==================== TEST 8: Single key + temporary 503 succeeds on retry ====================
    @Test
    void singleKeyTemporary503SucceedsOnRetry() {
        GeminiApiKeyManager manager = new GeminiApiKeyManager("", "only-key");
        GeminiRequestExecutor executor = new GeminiRequestExecutor(manager, 2);
        AtomicInteger attempts = new AtomicInteger();

        String result = executor.execute(apiKey -> {
            if (attempts.incrementAndGet() == 1) {
                throw responseStatus(503);
            }
            return "recovered";
        });

        assertThat(result).isEqualTo("recovered");
        assertThat(attempts).hasValue(2);
        assertThat(manager.activeKeyIndex()).isZero();
    }

    // ==================== TEST 9: All keys 503 -> GeminiAllKeysUnavailableException ====================
    @Test
    void allKeys503ExhaustedThrowsAllKeysUnavailable() {
        GeminiApiKeyManager manager = new GeminiApiKeyManager("key1,key2", "");
        GeminiRequestExecutor executor = new GeminiRequestExecutor(manager, 1);
        List<String> usedKeys = new ArrayList<>();

        GeminiAllKeysUnavailableException ex = assertThrows(GeminiAllKeysUnavailableException.class, () ->
                executor.execute(apiKey -> {
                    usedKeys.add(apiKey);
                    throw responseStatus(503);
                }));

        assertThat(ex.getErrorCode()).isEqualTo("GEMINI_SERVICE_UNAVAILABLE");
        // key1: 1 initial + 1 retry = 2 calls, key2: 1 initial + 1 retry = 2 calls
        assertThat(usedKeys).containsExactly("key1", "key1", "key2", "key2");
    }

    // ==================== TEST 10: Concurrency limiter serializes calls ====================
    @Test
    void concurrencyLimiterSerializesCalls() throws Exception {
        GeminiApiKeyManager manager = new GeminiApiKeyManager("", "key1");
        GeminiRequestExecutor executor = new GeminiRequestExecutor(manager, 1); // maxConcurrent=1
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(3);

        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                futures.add(pool.submit(() -> executor.execute(apiKey -> {
                    int c = concurrent.incrementAndGet();
                    maxConcurrent.updateAndGet(cur -> Math.max(cur, c));
                    try { Thread.sleep(50); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    concurrent.decrementAndGet();
                    return "done";
                })));
            }

            for (Future<String> f : futures) {
                assertThat(f.get(10, TimeUnit.SECONDS)).isEqualTo("done");
            }

            // Semaphore(1) means max concurrency should be 1
            assertThat(maxConcurrent.get()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ==================== TEST 16: Retry-After NOT capped at normal 15s maxDelay ====================
    @Test
    void retryAfterHeaderNotCappedAtNormalMaxDelay() {
        GeminiApiKeyManager manager = new GeminiApiKeyManager("key1", "");
        // Create executor with initialDelay=5000, maxDelay=15000 but maxRetryAfterMs=120000
        GeminiExtractionConcurrencyLimiter limiter = new GeminiExtractionConcurrencyLimiter(1);
        GeminiRequestExecutor executor = new GeminiRequestExecutor(
                manager, limiter, 2, 5000, 15000, 1000, 120000);
        AtomicInteger attempts = new AtomicInteger();
        long start = System.currentTimeMillis();

        String result = executor.execute(apiKey -> {
            if (attempts.incrementAndGet() == 1) {
                // Server says Retry-After: 30 seconds (which is > 15s maxDelayMs)
                throw responseStatusWithRetryAfter(503, "30");
            }
            return "ok";
        });

        long elapsed = System.currentTimeMillis() - start;
        assertThat(result).isEqualTo("ok");
        // Should have waited ~30s (Retry-After=30), NOT capped at 15s
        assertThat(elapsed).isGreaterThanOrEqualTo(28000L);
        assertThat(attempts).hasValue(2);
    }

    // ==================== TEST 17: 503 key NOT permanently unavailable ====================
    @Test
    void key503NotPermanentlyUnavailableForFutureOperations() {
        GeminiApiKeyManager manager = new GeminiApiKeyManager("key1,key2", "");
        GeminiRequestExecutor executor = new GeminiRequestExecutor(manager, 1);
        AtomicInteger key1Calls = new AtomicInteger();

        // First operation: key1 returns 503, failover to key2 succeeds
        executor.execute(apiKey -> {
            if ("key1".equals(apiKey)) {
                key1Calls.incrementAndGet();
                throw responseStatus(503);
            }
            return "first-ok";
        });

        assertThat(manager.isUnavailable(0)).isFalse();
        assertThat(manager.activeKeyIndex()).isEqualTo(1);

        // Second operation: key2 (now active) returns 429, should be able to failover BACK to key1
        key1Calls.set(0);
        List<String> secondUsedKeys = new ArrayList<>();

        String secondResult = executor.execute(apiKey -> {
            secondUsedKeys.add(apiKey);
            if ("key2".equals(apiKey)) {
                throw responseStatus(429);
            }
            return "second-ok";
        });

        assertThat(secondResult).isEqualTo("second-ok");
        assertThat(secondUsedKeys).contains("key1");
    }

    // ==================== TEST 18: Semaphore held through retry/backoff/failover ====================
    @Test
    void semaphoreHeldThroughEntireRetryAndFailoverSequence() throws Exception {
        GeminiApiKeyManager manager = new GeminiApiKeyManager("key1,key2", "");
        GeminiRequestExecutor executor = new GeminiRequestExecutor(manager, 1); // maxConcurrent=1
        AtomicInteger concurrentInFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        AtomicInteger key1Attempts = new AtomicInteger();

        // This operation will: key1 503 -> retry key1 503 -> failover key2 -> success
        // The semaphore must be held the entire time
        executor.execute(apiKey -> {
            int c = concurrentInFlight.incrementAndGet();
            maxInFlight.updateAndGet(cur -> Math.max(cur, c));
            try {
                if ("key1".equals(apiKey) && key1Attempts.incrementAndGet() <= 2) {
                    throw responseStatus(503);
                }
                return "ok";
            } finally {
                concurrentInFlight.decrementAndGet();
            }
        });

        // Even with retries and failover, the semaphore should never have allowed > 1 concurrent
        assertThat(maxInFlight.get()).isEqualTo(1);
    }

    // ==================== Network timeout retries ====================
    @Test
    void networkTimeoutRetriesOnSameKeyThenFailsOver() {
        GeminiApiKeyManager manager = new GeminiApiKeyManager("key1,key2", "");
        GeminiRequestExecutor executor = new GeminiRequestExecutor(manager, 1);
        List<String> usedKeys = new ArrayList<>();
        AtomicInteger key1Calls = new AtomicInteger();

        String result = executor.execute(apiKey -> {
            usedKeys.add(apiKey);
            if ("key1".equals(apiKey) && key1Calls.incrementAndGet() <= 2) {
                throw new org.springframework.web.client.ResourceAccessException("Connection timed out");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(usedKeys).containsExactly("key1", "key1", "key2");
        assertThat(manager.activeKeyIndex()).isEqualTo(1);
    }

    // ==================== 429 vs 503 differentiation ====================
    @Test
    void key429IsPermanentlyUnavailableButKey503IsNot() {
        GeminiApiKeyManager manager = new GeminiApiKeyManager("key1,key2,key3", "");
        GeminiRequestExecutor executor = new GeminiRequestExecutor(manager, 1);
        List<String> usedKeys = new ArrayList<>();

        executor.execute(apiKey -> {
            usedKeys.add(apiKey);
            if ("key1".equals(apiKey)) {
                throw responseStatus(429); // Should permanently disable key1
            }
            if ("key2".equals(apiKey)) {
                throw responseStatus(503); // Should NOT permanently disable key2
            }
            return "ok";
        });

        assertThat(manager.isUnavailable(0)).isTrue();  // 429 -> permanently unavailable
        assertThat(manager.isUnavailable(1)).isFalse();  // 503 -> NOT permanently unavailable
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

    private RestClientResponseException responseStatusWithRetryAfter(int statusCode, String retryAfterValue) {
        HttpStatus status = HttpStatus.valueOf(statusCode);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, retryAfterValue);
        return new RestClientResponseException(
                "Gemini status " + statusCode,
                statusCode,
                status.getReasonPhrase(),
                headers,
                "provider error".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8
        );
    }
}
