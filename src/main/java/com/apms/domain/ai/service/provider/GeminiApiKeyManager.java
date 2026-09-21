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
    private final AtomicInteger activeKeyIndex = new AtomicInteger(0);
    private final Set<Integer> unavailableKeyIndexes = ConcurrentHashMap.newKeySet();

    public GeminiApiKeyManager(@Value("${app.ai.gemini.api-keys:}") String configuredApiKeys,
                               @Value("${app.ai.gemini.api-key:}") String legacyApiKey) {
        List<String> configuredKeys = parseKeys(configuredApiKeys);
        this.apiKeys = configuredKeys.isEmpty() ? parseKeys(legacyApiKey) : configuredKeys;
        log.info("Configured {} Gemini API credential(s).", apiKeys.size());
    }

    public int keyCount() {
        return apiKeys.size();
    }

    public int activeKeyIndex() {
        return activeKeyIndex.get();
    }

    public Optional<GeminiApiKey> getCurrentKey() {
        if (apiKeys.isEmpty()) {
            return Optional.empty();
        }

        synchronized (this) {
            Optional<GeminiApiKey> current = firstAvailableFrom(activeKeyIndex.get(), Set.of());
            current.ifPresent(key -> activeKeyIndex.set(key.index()));
            return current;
        }
    }

    public Optional<GeminiApiKey> moveToNextKey(int failedKeyIndex) {
        return moveToNextKey(failedKeyIndex, Set.of());
    }

    public synchronized Optional<GeminiApiKey> moveToNextKey(int failedKeyIndex, Set<Integer> attemptedKeyIndexes) {
        if (failedKeyIndex >= 0 && failedKeyIndex < apiKeys.size()) {
            unavailableKeyIndexes.add(failedKeyIndex);
        }

        int currentIndex = activeKeyIndex.get();
        int searchStart = currentIndex > failedKeyIndex ? currentIndex : failedKeyIndex + 1;
        Optional<GeminiApiKey> nextKey = firstAvailableFrom(searchStart, attemptedKeyIndexes);
        nextKey.ifPresent(key -> activeKeyIndex.set(key.index()));
        return nextKey;
    }

    public boolean isUnavailable(int keyIndex) {
        return unavailableKeyIndexes.contains(keyIndex);
    }

    private Optional<GeminiApiKey> firstAvailableFrom(int startIndex, Set<Integer> attemptedKeyIndexes) {
        for (int index = Math.max(0, startIndex); index < apiKeys.size(); index++) {
            if (!unavailableKeyIndexes.contains(index) && !attemptedKeyIndexes.contains(index)) {
                return Optional.of(new GeminiApiKey(index, apiKeys.get(index)));
            }
        }
        return Optional.empty();
    }

    static List<String> parseKeys(String rawKeys) {
        if (!StringUtils.hasText(rawKeys)) {
            return List.of();
        }

        return Arrays.stream(rawKeys.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    public record GeminiApiKey(int index, String value) {
        public int displayIndex() {
            return index + 1;
        }
    }
}
