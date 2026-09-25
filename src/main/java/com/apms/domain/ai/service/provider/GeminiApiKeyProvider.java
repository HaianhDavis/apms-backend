package com.apms.domain.ai.service.provider;

import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.ai.dto.AiApiKeyDto;
import com.apms.domain.ai.dto.TestAiApiKeyResponse;
import com.apms.domain.ai.entity.AiApiKeyDocument;
import com.apms.domain.ai.entity.AiApiKeyStatus;
import com.apms.domain.ai.repository.mongo.AiApiKeyRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Component
public class GeminiApiKeyProvider {

    private final AiApiKeyRepository repository;
    private final AiApiKeyEncryptionService encryptionService;
    private final GeminiApiKeyManager keyManager;
    private final String bootstrapConfiguredKeys;
    private final String bootstrapLegacyKey;
    private final String geminiModel;
    private final RestClient restClient;

    private final CopyOnWriteArrayList<String> activeRawKeys = new CopyOnWriteArrayList<>();
    private final Map<String, String> rawKeyToDocId = new ConcurrentHashMap<>();

    @Autowired
    public GeminiApiKeyProvider(
            @Autowired(required = false) AiApiKeyRepository repository,
            AiApiKeyEncryptionService encryptionService,
            GeminiApiKeyManager keyManager,
            @Value("${app.ai.gemini.api-keys:}") String bootstrapConfiguredKeys,
            @Value("${app.ai.gemini.api-key:}") String bootstrapLegacyKey,
            @Value("${app.ai.gemini.model:gemini-3.8-flash}") String geminiModel) {
        this.repository = repository;
        this.encryptionService = encryptionService;
        this.keyManager = keyManager;
        this.bootstrapConfiguredKeys = bootstrapConfiguredKeys;
        this.bootstrapLegacyKey = bootstrapLegacyKey;
        this.geminiModel = geminiModel;
        this.restClient = RestClient.builder().build();
    }

    public GeminiApiKeyProvider(
            AiApiKeyRepository repository,
            AiApiKeyEncryptionService encryptionService,
            GeminiApiKeyManager keyManager,
            String bootstrapConfiguredKeys,
            String bootstrapLegacyKey) {
        this(repository, encryptionService, keyManager, bootstrapConfiguredKeys, bootstrapLegacyKey, "gemini-3.8-flash");
    }

    @PostConstruct
    public void init() {
        reload();
    }

    public List<String> getActiveKeys() {
        if (activeRawKeys.isEmpty()) {
            reload();
        }
        return Collections.unmodifiableList(new ArrayList<>(activeRawKeys));
    }

    public synchronized AiApiKeyDto addKey(String rawKey, String label) {
        if (!StringUtils.hasText(rawKey)) {
            throw new IllegalArgumentException("API key cannot be empty");
        }
        String cleanedKey = cleanKey(rawKey);

        String effectiveLabel = StringUtils.hasText(label) ? label.trim() : "Runtime Key #" + (activeRawKeys.size() + 1);
        String maskedKey = GeminiApiKeyMasker.maskKey(cleanedKey);

        AiApiKeyDocument doc = null;
        if (repository != null) {
            AiApiKeyEncryptionService.EncryptedSecret enc = encryptionService.encrypt(cleanedKey);
            int nextOrderIndex = calculateNextOrderIndex();
            doc = AiApiKeyDocument.builder()
                    .provider("GEMINI")
                    .maskedKey(maskedKey)
                    .encryptedKey(enc.cipherTextBase64())
                    .encryptionIv(enc.ivBase64())
                    .keyVersion(enc.keyVersion())
                    .label(effectiveLabel)
                    .status(AiApiKeyStatus.ACTIVE)
                    .active(true)
                    .orderIndex(nextOrderIndex)
                    .createdSource("ADMIN_API")
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
            doc = repository.save(doc);
            rawKeyToDocId.put(cleanedKey, doc.getId());
        }

        if (!activeRawKeys.contains(cleanedKey)) {
            activeRawKeys.add(cleanedKey);
        }

        if (keyManager != null) {
            keyManager.syncKeys(new ArrayList<>(activeRawKeys));
        }

        log.info("Added new Gemini API key ({}) at runtime. Active key count: {}", maskedKey, activeRawKeys.size());

        if (doc != null) {
            return toDto(doc);
        }
        return AiApiKeyDto.builder()
                .id(UUID.randomUUID().toString())
                .provider("GEMINI")
                .maskedKey(maskedKey)
                .label(effectiveLabel)
                .status(AiApiKeyStatus.ACTIVE)
                .active(true)
                .orderIndex(activeRawKeys.size() - 1)
                .createdSource("ADMIN_API")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    public AiApiKeyDto addKey(String rawKey) {
        return addKey(rawKey, null);
    }

    public synchronized boolean removeKey(String id) {
        if (repository != null && id != null) {
            Optional<AiApiKeyDocument> docOpt = repository.findById(id);
            if (docOpt.isPresent()) {
                repository.deleteById(id);
                log.info("Deleted Gemini API key id={} from storage.", id);
                reload();
                return true;
            }
        }
        boolean removed = activeRawKeys.removeIf(k -> k.equals(id) || GeminiApiKeyMasker.maskKey(k).equals(id));
        if (removed && keyManager != null) {
            keyManager.syncKeys(new ArrayList<>(activeRawKeys));
        }
        return removed;
    }

    public synchronized AiApiKeyDto setKeyEnabled(String id, boolean enabled) {
        if (repository != null && id != null) {
            Optional<AiApiKeyDocument> docOpt = repository.findById(id);
            if (docOpt.isPresent()) {
                AiApiKeyDocument doc = docOpt.get();
                doc.setActive(enabled);
                doc.setStatus(enabled ? AiApiKeyStatus.ACTIVE : AiApiKeyStatus.DISABLED);
                doc.setUpdatedAt(Instant.now());
                doc = repository.save(doc);

                try {
                    String decrypted = encryptionService.decrypt(doc.getEncryptedKey(), doc.getEncryptionIv(), doc.getKeyVersion());
                    if (enabled) {
                        if (!activeRawKeys.contains(decrypted)) {
                            activeRawKeys.add(decrypted);
                        }
                    } else {
                        activeRawKeys.remove(decrypted);
                    }
                    if (keyManager != null) {
                        keyManager.syncKeys(new ArrayList<>(activeRawKeys));
                    }
                } catch (Exception e) {
                    log.warn("Could not decrypt key for status change id={}: {}", id, e.getMessage());
                }
                log.info("Gemini API key id={} set enabled={}. Active key count: {}", id, enabled, activeRawKeys.size());
                return toDto(doc);
            }
        }
        throw new ResourceNotFoundException("API key not found with id: " + id);
    }

    public TestAiApiKeyResponse testKey(String id) {
        if (repository == null || id == null) {
            return new TestAiApiKeyResponse(false, "INVALID", "Storage not available");
        }
        Optional<AiApiKeyDocument> docOpt = repository.findById(id);
        if (docOpt.isEmpty()) {
            return new TestAiApiKeyResponse(false, "INVALID", "API key not found with id: " + id);
        }
        AiApiKeyDocument doc = docOpt.get();
        String plainKey;
        try {
            plainKey = encryptionService.decrypt(doc.getEncryptedKey(), doc.getEncryptionIv(), doc.getKeyVersion());
        } catch (Exception e) {
            return new TestAiApiKeyResponse(false, "INVALID", "Could not decrypt API key");
        }

        try {
            Map<String, Object> body = Map.of(
                    "contents", List.of(Map.of("parts", List.of(Map.of("text", "ping")))),
                    "generationConfig", Map.of("maxOutputTokens", 1)
            );
            String testModel = StringUtils.hasText(geminiModel) ? geminiModel : "gemini-2.5-flash";
            if (testModel.startsWith("models/")) {
                testModel = testModel.substring(7);
            }

            restClient.post()
                    .uri("https://generativelanguage.googleapis.com/v1/models/{model}:generateContent?key={key}", testModel, plainKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();

            doc.setStatus(AiApiKeyStatus.ACTIVE);
            doc.setActive(true);
            doc.setLastUsedAt(Instant.now());
            doc.setLastError(null);
            doc.setLastErrorCode(null);
            doc.setUpdatedAt(Instant.now());
            repository.save(doc);

            if (!activeRawKeys.contains(plainKey)) {
                activeRawKeys.add(plainKey);
                if (keyManager != null) {
                    keyManager.syncKeys(new ArrayList<>(activeRawKeys));
                }
            }

            return new TestAiApiKeyResponse(true, "ACTIVE", "API key is valid and available.");
        } catch (RestClientResponseException e) {
            int code = e.getStatusCode().value();
            if (code == 429) {
                doc.setStatus(AiApiKeyStatus.EXHAUSTED);
                doc.setLastErrorCode(429);
                doc.setLastError("RESOURCE_EXHAUSTED");
                doc.setLastFailureAt(Instant.now());
                doc.setUpdatedAt(Instant.now());
                repository.save(doc);
                activeRawKeys.remove(plainKey);
                if (keyManager != null) {
                    keyManager.syncKeys(new ArrayList<>(activeRawKeys));
                }
                return new TestAiApiKeyResponse(false, "EXHAUSTED", "API key quota is currently exhausted.");
            } else if (code == 401 || code == 403) {
                doc.setStatus(AiApiKeyStatus.INVALID);
                doc.setLastErrorCode(code);
                doc.setLastError(code == 401 ? "UNAUTHORIZED" : "FORBIDDEN");
                doc.setLastFailureAt(Instant.now());
                doc.setUpdatedAt(Instant.now());
                repository.save(doc);
                activeRawKeys.remove(plainKey);
                if (keyManager != null) {
                    keyManager.syncKeys(new ArrayList<>(activeRawKeys));
                }
                return new TestAiApiKeyResponse(false, "INVALID", "API key is invalid or unauthorized.");
            } else {
                return new TestAiApiKeyResponse(false, doc.getStatus() != null ? doc.getStatus().name() : "ERROR", "Test failed with status " + code);
            }
        } catch (Exception e) {
            return new TestAiApiKeyResponse(false, doc.getStatus() != null ? doc.getStatus().name() : "ERROR", "Test failed: " + e.getMessage());
        }
    }

    public synchronized void recordKeySuccess(String rawKey) {
        if (rawKey == null) return;
        String docId = rawKeyToDocId.get(rawKey);
        if (docId != null && repository != null) {
            repository.findById(docId).ifPresent(doc -> {
                doc.setLastUsedAt(Instant.now());
                if (doc.getStatus() != AiApiKeyStatus.DISABLED) {
                    doc.setStatus(AiApiKeyStatus.ACTIVE);
                }
                repository.save(doc);
            });
        }
    }

    public synchronized void recordKeyFailure(String rawKey, int errorCode, String errorMessage, AiApiKeyStatus targetStatus) {
        if (rawKey == null) return;
        String docId = rawKeyToDocId.get(rawKey);
        if (docId != null && repository != null) {
            repository.findById(docId).ifPresent(doc -> {
                doc.setStatus(targetStatus);
                doc.setLastErrorCode(errorCode);
                doc.setLastError(errorMessage);
                doc.setLastFailureAt(Instant.now());
                doc.setUpdatedAt(Instant.now());
                repository.save(doc);
                log.info("Persisted status={} (error={}) for Gemini API key id={}", targetStatus, errorCode, doc.getId());
            });
        }
    }

    public synchronized void replaceKeys(List<String> rawKeys) {
        if (rawKeys == null || rawKeys.isEmpty()) {
            throw new IllegalArgumentException("Key list cannot be empty for replaceKeys");
        }

        List<String> cleanedKeys = rawKeys.stream()
                .map(this::cleanKey)
                .filter(StringUtils::hasText)
                .toList();

        if (cleanedKeys.isEmpty()) {
            throw new IllegalArgumentException("No valid keys provided");
        }

        rawKeyToDocId.clear();
        if (repository != null) {
            repository.deleteAll();
            for (int i = 0; i < cleanedKeys.size(); i++) {
                String key = cleanedKeys.get(i);
                AiApiKeyEncryptionService.EncryptedSecret enc = encryptionService.encrypt(key);
                AiApiKeyDocument doc = AiApiKeyDocument.builder()
                        .provider("GEMINI")
                        .maskedKey(GeminiApiKeyMasker.maskKey(key))
                        .encryptedKey(enc.cipherTextBase64())
                        .encryptionIv(enc.ivBase64())
                        .keyVersion(enc.keyVersion())
                        .label("Key #" + (i + 1))
                        .status(AiApiKeyStatus.ACTIVE)
                        .active(true)
                        .orderIndex(i)
                        .createdSource("ADMIN_API")
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();
                doc = repository.save(doc);
                rawKeyToDocId.put(key, doc.getId());
            }
        }

        activeRawKeys.clear();
        activeRawKeys.addAll(cleanedKeys);

        if (keyManager != null) {
            keyManager.resetAvailability();
            keyManager.syncKeys(new ArrayList<>(activeRawKeys));
        }

        log.info("Replaced all Gemini API keys with {} new keys.", activeRawKeys.size());
    }

    public synchronized void reload() {
        rawKeyToDocId.clear();
        if (repository != null) {
            try {
                List<AiApiKeyDocument> persisted = repository.findAllByOrderByOrderIndexAscCreatedAtAsc();
                if (!persisted.isEmpty()) {
                    List<String> loaded = new ArrayList<>();
                    for (AiApiKeyDocument doc : persisted) {
                        try {
                            String decrypted = encryptionService.decrypt(doc.getEncryptedKey(), doc.getEncryptionIv(), doc.getKeyVersion());
                            if (StringUtils.hasText(decrypted)) {
                                String clean = decrypted.trim();
                                rawKeyToDocId.put(clean, doc.getId());
                                if (doc.isActive() && doc.getStatus() == AiApiKeyStatus.ACTIVE) {
                                    loaded.add(clean);
                                }
                            }
                        } catch (Exception e) {
                            log.warn("Skipping unreadable Gemini key id={}: {}", doc.getId(), e.getMessage());
                        }
                    }
                    activeRawKeys.clear();
                    activeRawKeys.addAll(loaded);
                    if (keyManager != null) {
                        keyManager.syncKeys(new ArrayList<>(activeRawKeys));
                    }
                    log.info("GeminiApiKeyProvider reloaded {} active keys from storage.", activeRawKeys.size());
                    return;
                }
            } catch (Exception e) {
                log.warn("Could not load keys from repository: {}. Attempting bootstrap.", e.getMessage());
            }
        }

        bootstrapFromConfig();
    }

    private void bootstrapFromConfig() {
        List<String> parsedKeys = GeminiApiKeyManager.parseKeys(bootstrapConfiguredKeys);
        if (parsedKeys.isEmpty()) {
            parsedKeys = GeminiApiKeyManager.parseKeys(bootstrapLegacyKey);
        }
        List<String> validKeys = parsedKeys.stream()
                .filter(k -> !"dummy-key".equalsIgnoreCase(k))
                .map(this::cleanKey)
                .filter(StringUtils::hasText)
                .toList();

        if (validKeys.isEmpty()) {
            validKeys = parsedKeys.stream()
                    .map(this::cleanKey)
                    .filter(StringUtils::hasText)
                    .toList();
        }

        activeRawKeys.clear();
        activeRawKeys.addAll(validKeys);

        if (repository != null && !validKeys.isEmpty()) {
            try {
                for (int i = 0; i < validKeys.size(); i++) {
                    String key = validKeys.get(i);
                    AiApiKeyEncryptionService.EncryptedSecret enc = encryptionService.encrypt(key);
                    AiApiKeyDocument doc = AiApiKeyDocument.builder()
                            .provider("GEMINI")
                            .maskedKey(GeminiApiKeyMasker.maskKey(key))
                            .encryptedKey(enc.cipherTextBase64())
                            .encryptionIv(enc.ivBase64())
                            .keyVersion(enc.keyVersion())
                            .label("Bootstrap Key #" + (i + 1))
                            .status(AiApiKeyStatus.ACTIVE)
                            .active(true)
                            .orderIndex(i)
                            .createdSource("BOOTSTRAP")
                            .createdAt(Instant.now())
                            .updatedAt(Instant.now())
                            .build();
                    doc = repository.save(doc);
                    rawKeyToDocId.put(key, doc.getId());
                }
            } catch (Exception e) {
                log.warn("Failed to persist bootstrap keys to repository: {}", e.getMessage());
            }
        }

        if (keyManager != null) {
            keyManager.resetAvailability();
            keyManager.syncKeys(new ArrayList<>(activeRawKeys));
        }

        log.info("GeminiApiKeyProvider initialized with {} bootstrap keys.", activeRawKeys.size());
    }

    public List<AiApiKeyDto> getAllKeys() {
        if (repository != null) {
            List<AiApiKeyDocument> docs = repository.findAllByOrderByOrderIndexAscCreatedAtAsc();
            if (!docs.isEmpty()) {
                return docs.stream().map(this::toDto).toList();
            }
        }
        List<AiApiKeyDto> list = new ArrayList<>();
        for (int i = 0; i < activeRawKeys.size(); i++) {
            String key = activeRawKeys.get(i);
            list.add(AiApiKeyDto.builder()
                    .id("in-memory-" + i)
                    .provider("GEMINI")
                    .maskedKey(GeminiApiKeyMasker.maskKey(key))
                    .label("Active Key #" + (i + 1))
                    .status(AiApiKeyStatus.ACTIVE)
                    .active(true)
                    .orderIndex(i)
                    .createdSource("BOOTSTRAP")
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build());
        }
        return list;
    }

    private int calculateNextOrderIndex() {
        if (repository == null) return activeRawKeys.size();
        List<AiApiKeyDocument> docs = repository.findAllByOrderByOrderIndexAscCreatedAtAsc();
        return docs.stream().mapToInt(AiApiKeyDocument::getOrderIndex).max().orElse(-1) + 1;
    }

    private String cleanKey(String raw) {
        if (raw == null) return "";
        return raw.trim().replaceAll("^[`'\"\\s]+|[`'\"\\s]+$", "");
    }

    private AiApiKeyDto toDto(AiApiKeyDocument doc) {
        return AiApiKeyDto.builder()
                .id(doc.getId())
                .provider(doc.getProvider() != null ? doc.getProvider() : "GEMINI")
                .maskedKey(doc.getMaskedKey())
                .label(doc.getLabel())
                .status(doc.getStatus() != null ? doc.getStatus() : (doc.isActive() ? AiApiKeyStatus.ACTIVE : AiApiKeyStatus.DISABLED))
                .lastUsedAt(doc.getLastUsedAt())
                .lastFailureAt(doc.getLastFailureAt())
                .lastErrorCode(doc.getLastErrorCode())
                .lastError(doc.getLastError())
                .active(doc.isActive())
                .orderIndex(doc.getOrderIndex())
                .createdSource(doc.getCreatedSource())
                .createdAt(doc.getCreatedAt())
                .updatedAt(doc.getUpdatedAt())
                .build();
    }
}
