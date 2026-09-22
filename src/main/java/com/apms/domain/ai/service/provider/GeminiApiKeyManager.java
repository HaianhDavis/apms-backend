package com.apms.domain.ai.service.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class GeminiApiKeyManager {

    private final List<String> apiKeys;
    private final String credentialSource;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.core.env.ConfigurableEnvironment environment;
    private final AtomicInteger activeKeyIndex = new AtomicInteger(0);
    private final Set<Integer> unavailableKeyIndexes = ConcurrentHashMap.newKeySet();

    public GeminiApiKeyManager(@Value("${app.ai.gemini.api-keys:}") String configuredApiKeys,
                               @Value("${app.ai.gemini.api-key:}") String legacyApiKey) {
        List<String> configuredKeys = parseKeys(configuredApiKeys);
        List<String> legacyKeys = parseKeys(legacyApiKey);

        List<String> validConfigured = configuredKeys.stream().filter(k -> !"dummy-key".equalsIgnoreCase(k)).toList();
        List<String> validLegacy = legacyKeys.stream().filter(k -> !"dummy-key".equalsIgnoreCase(k)).toList();

        if (!validConfigured.isEmpty()) {
            this.apiKeys = validConfigured;
            this.credentialSource = "app.ai.gemini.api-keys";
        } else if (!validLegacy.isEmpty()) {
            this.apiKeys = validLegacy;
            this.credentialSource = "app.ai.gemini.api-key";
        } else if (!configuredKeys.isEmpty()) {
            this.apiKeys = configuredKeys;
            this.credentialSource = "app.ai.gemini.api-keys";
        } else {
            this.apiKeys = legacyKeys;
            this.credentialSource = "app.ai.gemini.api-key";
        }
        log.info("Gemini credentials configured: {}", apiKeys.size());
        if (!apiKeys.isEmpty()) {
            log.info("Active Gemini credential: #{}", activeKeyIndex.get() + 1);
        }
    }

    @jakarta.annotation.PostConstruct
    void logCredentialMetadata() {
        if (!log.isDebugEnabled()) return;
        log.debug("Gemini selected property: {}", credentialSource);
        if (environment != null) {
            for (String property : List.of(credentialSource, "GEMINI_API_KEYS", "GEMINI_API_KEY")) {
                for (org.springframework.core.env.PropertySource<?> source : environment.getPropertySources()) {
                    if (!"configurationProperties".equals(source.getName()) && source.containsProperty(property)) {
                        log.debug("Gemini property {} supplied by {}", property, source.getName());
                        break;
                    }
                }
            }
        }
        for (int i = 0; i < apiKeys.size(); i++) {
            String key = apiKeys.get(i);
            log.debug("Gemini credential #{}: {}, source={}, dummy={}, duplicate={}",
                    i + 1, GeminiCredentialDiagnostics.describeKey(key), credentialSource,
                    "dummy-key".equalsIgnoreCase(key), apiKeys.indexOf(key) != i);
        }
    }

    public int keyCount() {
        return apiKeys.size();
    }

    public int activeKeyIndex() {
        return activeKeyIndex.get();
    }

    public synchronized Optional<GeminiApiKey> getCurrentKey() {
        if (apiKeys.isEmpty()) {
            return Optional.empty();
        }

        if (unavailableKeyIndexes.size() >= apiKeys.size()) {
            log.info("All Gemini keys were marked permanently unavailable. Resetting key availability status.");
            unavailableKeyIndexes.clear();
        }

        Optional<GeminiApiKey> current = firstAvailableFrom(activeKeyIndex.get(), Set.of());
        if (current.isEmpty()) {
            unavailableKeyIndexes.clear();
            current = firstAvailableFrom(0, Set.of());
        }
        current.ifPresent(key -> activeKeyIndex.set(key.index()));
        return current;
    }

    /**
     * Thread-safe atomic failover from a failed credential.
     *
     * If another concurrent thread has already advanced activeKeyIndex to a new key that has
     * not been attempted in the current operation, this thread picks up that new active key
     * without skipping keys.
     *
     * @param failedKeyIndex index of the key that failed
     * @param attemptedInThisOperation set of key indices already attempted in the caller's operation
     * @param permanentLockout true for 401/403/429 where the key is invalid or quota-exhausted;
     *                         false for 503/transient where the key remains valid for future operations
     */
    public synchronized Optional<GeminiApiKey> failoverFrom(int failedKeyIndex,
                                                            Set<Integer> attemptedInThisOperation,
                                                            boolean permanentLockout) {
        if (permanentLockout && failedKeyIndex >= 0 && failedKeyIndex < apiKeys.size()) {
            unavailableKeyIndexes.add(failedKeyIndex);
            log.warn("Gemini credential #{} marked permanently unavailable.", failedKeyIndex + 1);
        }

        // If another thread already rotated to a key that is usable for this operation, use it!
        int currentActive = activeKeyIndex.get();
        if (currentActive != failedKeyIndex && isUsable(currentActive, attemptedInThisOperation)) {
            log.info("Concurrent failover detected: adopting already-rotated Gemini credential #{}.", currentActive + 1);
            return Optional.of(new GeminiApiKey(currentActive, apiKeys.get(currentActive)));
        }

        // Otherwise find the next usable key starting from failedKeyIndex + 1
        Optional<GeminiApiKey> nextKey = firstAvailableFrom(failedKeyIndex + 1, attemptedInThisOperation);
        if (nextKey.isEmpty() && failedKeyIndex > 0) {
            // Wrap around to search from index 0 up to failedKeyIndex
            nextKey = firstAvailableFrom(0, attemptedInThisOperation);
        }

        nextKey.ifPresent(key -> {
            activeKeyIndex.set(key.index());
            log.info("Advanced active Gemini credential to #{}.", key.displayIndex());
        });
        return nextKey;
    }

    public synchronized void recordSuccess(int keyIndex) {
        if (keyIndex >= 0 && keyIndex < apiKeys.size()) {
            activeKeyIndex.set(keyIndex);
        }
    }

    public Optional<GeminiApiKey> moveToNextKey(int failedKeyIndex) {
        return moveToNextKey(failedKeyIndex, Set.of());
    }

    public synchronized Optional<GeminiApiKey> moveToNextKey(int failedKeyIndex, Set<Integer> attemptedKeyIndexes) {
        return failoverFrom(failedKeyIndex, attemptedKeyIndexes, false);
    }

    public boolean isUnavailable(int keyIndex) {
        return unavailableKeyIndexes.contains(keyIndex);
    }

    private boolean isUsable(int index, Set<Integer> attempted) {
        return index >= 0 && index < apiKeys.size()
                && !unavailableKeyIndexes.contains(index)
                && !attempted.contains(index);
    }

    private Optional<GeminiApiKey> firstAvailableFrom(int startIndex, Set<Integer> attemptedKeyIndexes) {
        if (apiKeys.isEmpty()) {
            return Optional.empty();
        }
        int total = apiKeys.size();
        for (int offset = 0; offset < total; offset++) {
            int index = (startIndex + offset) % total;
            if (isUsable(index, attemptedKeyIndexes)) {
                return Optional.of(new GeminiApiKey(index, apiKeys.get(index)));
            }
        }
        return Optional.empty();
    }

    static List<String> parseKeys(String rawKeys) {
        if (!StringUtils.hasText(rawKeys)) {
            return List.of();
        }

        if (rawKeys.indexOf(';') >= 0 || rawKeys.indexOf('\n') >= 0 || rawKeys.indexOf('\r') >= 0) {
            log.warn("Gemini credential configuration contains semicolon/newline; only commas separate credentials.");
        }

        return Arrays.stream(rawKeys.split(","))
                .map(String::trim)
                .map(k -> k.replaceAll("^[`'\"\\s]+|[`'\"\\s]+$", ""))
                .filter(StringUtils::hasText)
                .toList();
    }

    /**
     * Returns the primary API key as a plain string.
     * Single source of truth — all services should call this instead of injecting @Value directly.
     */
    public String getApiKey() {
        if (!apiKeys.isEmpty()) {
            return apiKeys.get(activeKeyIndex.get() % apiKeys.size());
        }
        return "";
    }

    public record GeminiApiKey(int index, String value) {
        public int displayIndex() {
            return index + 1;
        }
    }
}
