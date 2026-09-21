package com.apms.domain.ai.service.provider;

import com.apms.common.exception.BusinessValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Component
public class GeminiRequestExecutor {

    private final GeminiApiKeyManager keyManager;
    private final int transientAttempts;

    public GeminiRequestExecutor(GeminiApiKeyManager keyManager,
                                 @Value("${gemini.retry.transient-attempts:1}") int transientAttempts) {
        this.keyManager = keyManager;
        this.transientAttempts = Math.max(0, transientAttempts);
    }

    public <T> T execute(GeminiOperation<T> operation) {
        Set<Integer> attemptedKeyIndexes = new HashSet<>();
        Optional<GeminiApiKeyManager.GeminiApiKey> currentKey = keyManager.getCurrentKey();

        while (currentKey.isPresent() && attemptedKeyIndexes.size() < keyManager.keyCount()) {
            GeminiApiKeyManager.GeminiApiKey key = currentKey.get();
            attemptedKeyIndexes.add(key.index());

            try {
                return executeWithTransientRetry(operation, key);
            } catch (RestClientResponseException e) {
                int statusCode = e.getStatusCode().value();
                if (!shouldSwitchKey(statusCode)) {
                    throw mapNonFailoverResponse(statusCode);
                }

                currentKey = failover(key, attemptedKeyIndexes, describeStatus(statusCode));
            } catch (ResourceAccessException e) {
                currentKey = failover(key, attemptedKeyIndexes, "network timeout");
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
                return operation.execute(key.value());
            } catch (RestClientResponseException e) {
                int statusCode = e.getStatusCode().value();
                if (!isTransientStatus(statusCode) || attempt >= transientAttempts) {
                    throw e;
                }
                attempt++;
                log.warn("Gemini key #{} returned transient status {}. Retry {}/{} on the current key.",
                        key.displayIndex(), statusCode, attempt, transientAttempts);
            } catch (ResourceAccessException e) {
                if (attempt >= transientAttempts) {
                    throw e;
                }
                attempt++;
                log.warn("Gemini key #{} hit a transient network error. Retry {}/{} on the current key.",
                        key.displayIndex(), attempt, transientAttempts);
            }
        }
    }

    private Optional<GeminiApiKeyManager.GeminiApiKey> failover(GeminiApiKeyManager.GeminiApiKey failedKey,
                                                                Set<Integer> attemptedKeyIndexes,
                                                                String reason) {
        Optional<GeminiApiKeyManager.GeminiApiKey> nextKey =
                keyManager.moveToNextKey(failedKey.index(), attemptedKeyIndexes);
        if (nextKey.isPresent()) {
            log.warn("Gemini key #{} returned {}. Switching to key #{}.",
                    failedKey.displayIndex(), reason, nextKey.get().displayIndex());
            return nextKey;
        }

        log.error("Gemini request failed after trying all configured credentials.");
        throw new GeminiAllKeysUnavailableException();
    }

    private boolean shouldSwitchKey(int statusCode) {
        return statusCode == 401
                || statusCode == 403
                || statusCode == 429
                || isTransientStatus(statusCode);
    }

    private boolean isTransientStatus(int statusCode) {
        return statusCode == 500
                || statusCode == 502
                || statusCode == 503
                || statusCode == 504;
    }

    private BusinessValidationException mapNonFailoverResponse(int statusCode) {
        if (statusCode == 400) {
            log.error("Gemini rejected the request with status 400.");
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
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
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
