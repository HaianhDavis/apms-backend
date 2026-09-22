package com.apms.domain.ai.service.provider;

import com.apms.common.exception.BusinessValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Component
public class GeminiRequestExecutor {

    private final GeminiApiKeyManager keyManager;
    private final GeminiExtractionConcurrencyLimiter concurrencyLimiter;
    private final int transientAttempts;
    private final long initialDelayMs;
    private final long maxDelayMs;
    private final long jitterMs;
    private final long maxRetryAfterMs;

    @Autowired
    public GeminiRequestExecutor(
            GeminiApiKeyManager keyManager,
            GeminiExtractionConcurrencyLimiter concurrencyLimiter,
            @Value("${app.ai.gemini.retry.transient-attempts:${gemini.retry.transient-attempts:2}}") int transientAttempts,
            @Value("${app.ai.gemini.retry.initial-delay-ms:5000}") long initialDelayMs,
            @Value("${app.ai.gemini.retry.max-delay-ms:15000}") long maxDelayMs,
            @Value("${app.ai.gemini.retry.jitter-ms:1000}") long jitterMs,
            @Value("${app.ai.gemini.retry.max-retry-after-ms:120000}") long maxRetryAfterMs) {
        this.keyManager = keyManager;
        this.concurrencyLimiter = concurrencyLimiter;
        this.transientAttempts = Math.max(0, transientAttempts);
        this.initialDelayMs = Math.max(0, initialDelayMs);
        this.maxDelayMs = Math.max(0, maxDelayMs);
        this.jitterMs = Math.max(0, jitterMs);
        this.maxRetryAfterMs = Math.max(1000, maxRetryAfterMs);
    }

    /**
     * Test-friendly constructor with zero delays for fast unit tests.
     */
    public GeminiRequestExecutor(GeminiApiKeyManager keyManager, int transientAttempts) {
        this(keyManager, new GeminiExtractionConcurrencyLimiter(1), transientAttempts, 0, 0, 0, 120000);
    }

    /**
     * Test-friendly constructor with configurable concurrency for concurrent tests.
     */
    public GeminiRequestExecutor(GeminiApiKeyManager keyManager, int transientAttempts, int maxConcurrent) {
        this(keyManager, new GeminiExtractionConcurrencyLimiter(maxConcurrent), transientAttempts, 0, 0, 0, 120000);
    }

    /**
     * Executes the Gemini operation while holding a concurrency permit across the entire
     * logical operation (including all transient retries, backoff waits, and credential failovers).
     */
    public <T> T execute(GeminiOperation<T> operation) {
        try {
            return concurrencyLimiter.executeWithPermit(() -> executeInternal(operation));
        } catch (BusinessValidationException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new BusinessValidationException("Gemini execution failed: " + e.getMessage());
        }
    }

    private <T> T executeInternal(GeminiOperation<T> operation) {
        Set<Integer> attemptedKeyIndexes = new HashSet<>();
        Optional<GeminiApiKeyManager.GeminiApiKey> currentKey = keyManager.getCurrentKey();

        while (currentKey.isPresent() && attemptedKeyIndexes.size() < keyManager.keyCount()) {
            GeminiApiKeyManager.GeminiApiKey key = currentKey.get();
            attemptedKeyIndexes.add(key.index());

            try {
                T result = executeWithTransientRetry(operation, key);
                // On success, update active key
                keyManager.recordSuccess(key.index());
                return result;
            } catch (RestClientResponseException e) {
                int statusCode = e.getStatusCode().value();
                if (statusCode == 400 || statusCode == 404) {
                    throw mapNonFailoverResponse(statusCode);
                }

                // For 401/403 or 429, mark credential permanently exhausted for this session
                // For 503 or transient status where retries were exhausted, do NOT permanently disable key
                boolean permanentLockout = (statusCode == 401 || statusCode == 403 || statusCode == 429);
                currentKey = failover(key, attemptedKeyIndexes, describeStatus(statusCode), permanentLockout);
            } catch (ResourceAccessException e) {
                // Transient network error exhausted on this key
                currentKey = failover(key, attemptedKeyIndexes, "network timeout", false);
            }
        }

        log.error("Gemini request failed after trying all configured credentials.");
        throw new GeminiAllKeysUnavailableException();
    }

    private <T> T executeWithTransientRetry(GeminiOperation<T> operation,
                                            GeminiApiKeyManager.GeminiApiKey key) {
        int attempt = 0;
        while (true) {
            try {
                log.debug("Executing Gemini request using credential #{} ({})",
                        key.displayIndex(), GeminiCredentialDiagnostics.describeKey(key.value()));
                return operation.execute(key.value());
            } catch (RestClientResponseException e) {
                int statusCode = e.getStatusCode().value();

                // Non-retryable client errors
                if (statusCode == 400 || statusCode == 404) {
                    throw e;
                }

                // 401/403 or 429: do not retry same key, throw to trigger immediate rotation
                if (statusCode == 401 || statusCode == 403 || statusCode == 429) {
                    log.warn("Gemini credential #{} returned {}. Switching credential without repeating on current key.",
                            key.displayIndex(), describeStatus(statusCode));
                    throw e;
                }

                // Transient server errors (500, 502, 503, 504)
                if (!isTransientStatus(statusCode) || attempt >= transientAttempts) {
                    if (isTransientStatus(statusCode)) {
                        log.warn("Gemini credential #{} exhausted {} transient retries for status {}.",
                                key.displayIndex(), transientAttempts, statusCode);
                    }
                    throw e;
                }

                attempt++;
                long delayMs = calculateDelay(e, attempt);
                log.warn("Gemini credential #{} returned {}. Transient retry {}/{} in {} ms.",
                        key.displayIndex(), describeStatus(statusCode), attempt, transientAttempts, delayMs);
                sleepQuietly(delayMs);

            } catch (ResourceAccessException e) {
                if (attempt >= transientAttempts) {
                    log.warn("Gemini credential #{} exhausted {} network timeout retries.",
                            key.displayIndex(), transientAttempts);
                    throw e;
                }
                attempt++;
                long delayMs = calculateDelay(null, attempt);
                log.warn("Gemini credential #{} hit transient network error. Retry {}/{} in {} ms.",
                        key.displayIndex(), attempt, transientAttempts, delayMs);
                sleepQuietly(delayMs);
            }
        }
    }

    private long calculateDelay(RestClientResponseException exception, int attempt) {
        if (exception != null) {
            Long retryAfterMs = extractRetryAfterMs(exception);
            if (retryAfterMs != null) {
                // Provider specified Retry-After: cap at maxRetryAfterMs (not the normal 15s maxDelayMs)
                return Math.min(maxRetryAfterMs, Math.max(1000L, retryAfterMs));
            }
        }

        // Configured exponential backoff + jitter
        if (initialDelayMs <= 0) {
            return 0;
        }
        long exponential = (long) (initialDelayMs * Math.pow(2, attempt - 1));
        long bounded = Math.min(maxDelayMs, exponential);
        long jitter = jitterMs > 0 ? (long) (ThreadLocalRandom.current().nextDouble() * jitterMs) : 0;
        return bounded + jitter;
    }

    private Long extractRetryAfterMs(RestClientResponseException exception) {
        try {
            HttpHeaders headers = exception.getResponseHeaders();
            if (headers == null) return null;
            String headerVal = headers.getFirst(HttpHeaders.RETRY_AFTER);
            if (headerVal == null || headerVal.isBlank()) return null;

            headerVal = headerVal.trim();
            // Case 1: Delay in seconds (e.g., "12")
            if (headerVal.matches("^\\d+$")) {
                long seconds = Long.parseLong(headerVal);
                return seconds * 1000L;
            }

            // Case 2: HTTP Date (e.g., "Wed, 21 Oct 2026 07:28:00 GMT")
            try {
                Instant instant = DateTimeFormatter.RFC_1123_DATE_TIME.parse(headerVal, Instant::from);
                long delayMs = instant.toEpochMilli() - System.currentTimeMillis();
                return Math.max(0, delayMs);
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            log.debug("Could not parse Retry-After header: {}", e.getMessage());
        }
        return null;
    }

    private void sleepQuietly(long delayMs) {
        if (delayMs <= 0) return;
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new BusinessValidationException("Gemini request interrupted during backoff wait.");
        }
    }

    private Optional<GeminiApiKeyManager.GeminiApiKey> failover(
            GeminiApiKeyManager.GeminiApiKey failedKey,
            Set<Integer> attemptedKeyIndexes,
            String reason,
            boolean permanentLockout) {

        Optional<GeminiApiKeyManager.GeminiApiKey> nextKey =
                keyManager.failoverFrom(failedKey.index(), attemptedKeyIndexes, permanentLockout);

        if (nextKey.isPresent()) {
            log.warn("Gemini credential #{} returned {}. Switching to credential #{}.",
                    failedKey.displayIndex(), reason, nextKey.get().displayIndex());
            return nextKey;
        }

        log.error("Gemini credential #{} returned {} and no configured fallback credentials remain.",
                failedKey.displayIndex(), reason);
        log.error("Gemini request failed after trying all configured credentials.");
        throw new GeminiAllKeysUnavailableException();
    }

    private boolean isTransientStatus(int statusCode) {
        return statusCode == 500
                || statusCode == 502
                || statusCode == 503
                || statusCode == 504;
    }

    private BusinessValidationException mapNonFailoverResponse(int statusCode) {
        if (statusCode == 400) {
            log.error("Gemini rejected the request with status 400 (Bad Request). Check prompt and schema formatting.");
            return new BusinessValidationException("GEMINI_INVALID_REQUEST");
        }
        if (statusCode == 404) {
            log.error("Gemini model unavailable with status 404.");
            return new BusinessValidationException("GEMINI_MODEL_UNAVAILABLE");
        }

        log.error("Gemini API call failed with status {}.", statusCode);
        return new BusinessValidationException("Gemini API call failed: " + statusCode);
    }

    private String describeStatus(int statusCode) {
        return switch (statusCode) {
            case 400 -> "BAD_REQUEST";
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 429 -> "RESOURCE_EXHAUSTED";
            case 500 -> "INTERNAL_SERVER_ERROR";
            case 502 -> "BAD_GATEWAY";
            case 503 -> "SERVICE_UNAVAILABLE";
            case 504 -> "GATEWAY_TIMEOUT";
            default -> "status " + statusCode;
        };
    }

    @FunctionalInterface
    public interface GeminiOperation<T> {
        T execute(String apiKey);
    }
}
