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
            String bootstrapLegacyKey,
            String geminiModel,
            RestClient restClient) {
        this.repository = repository;
        this.encryptionService = encryptionService;
        this.keyManager = keyManager;
        this.bootstrapConfiguredKeys = bootstrapConfiguredKeys;
        this.bootstrapLegacyKey = bootstrapLegacyKey;
        this.geminiModel = geminiModel;
        this.restClient = restClient != null ? restClient : RestClient.builder().build();
    }

    public GeminiApiKeyProvider(
            AiApiKeyRepository repository,
            AiApiKeyEncryptionService encryptionService,
            GeminiApiKeyManager keyManager,
            String bootstrapConfiguredKeys,
            String bootstrapLegacyKey,
            RestClient restClient) {
        this(repository, encryptionService, keyManager, bootstrapConfiguredKeys, bootstrapLegacyKey, "gemini-3.8-flash", restClient);
    }

    public GeminiApiKeyProvider(
            AiApiKeyRepository repository,
            AiApiKeyEncryptionService encryptionService,
            GeminiApiKeyManager keyManager,
            String bootstrapConfiguredKeys,
            String bootstrapLegacyKey) {
        this(repository, encryptionService, keyManager, bootstrapConfiguredKeys, bootstrapLegacyKey, "gemini-3.8-flash", null);
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

    record KeyValidationResult(
            boolean success,
            AiApiKeyStatus status,
            Integer errorCode,
            String error,
            String message,
            boolean transientError
    ) {}

    private KeyValidationResult validateKey(String plainKey) {
        if (!StringUtils.hasText(plainKey)) {
            return new KeyValidationResult(false, AiApiKeyStatus.INVALID, 400, "INVALID_KEY", "API key cannot be empty", false);
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

            return new KeyValidationResult(true, AiApiKeyStatus.ACTIVE, null, null, "API key is valid and available.", false);
        } catch (RestClientResponseException e) {
            int code = e.getStatusCode().value();
            if (code == 429) {
                return new KeyValidationResult(false, AiApiKeyStatus.EXHAUSTED, 429, "RESOURCE_EXHAUSTED", "API key quota is currently exhausted.", false);
            } else if (code == 401 || code == 403) {
                String err = code == 401 ? "UNAUTHORIZED" : "FORBIDDEN";
                return new KeyValidationResult(false, AiApiKeyStatus.INVALID, code, err, "API key is invalid or unauthorized.", false);
            } else if (code == 503 || code == 502 || code == 504) {
                return new KeyValidationResult(false, null, code, "SERVICE_UNAVAILABLE", "Gemini service temporarily unavailable (HTTP " + code + ").", true);
            } else {
                return new KeyValidationResult(false, AiApiKeyStatus.INVALID, code, "HTTP_" + code, "Validation failed with status " + code, false);
            }
        } catch (Exception e) {
            return new KeyValidationResult(false, null, null, "NETWORK_ERROR", "Validation request failed: " + e.getMessage(), true);
        }
    }

    public synchronized AiApiKeyDto setKeyEnabled(String id, boolean enabled) {
        if (repository != null && id != null) {
            Optional<AiApiKeyDocument> docOpt = repository.findById(id);
            if (docOpt.isPresent()) {
                AiApiKeyDocument doc = docOpt.get();
                if (!enabled) {
                    doc.setActive(false);
                    doc.setStatus(AiApiKeyStatus.DISABLED);
                    doc.setUpdatedAt(Instant.now());
                    doc = repository.save(doc);

                    try {
                        String decrypted = encryptionService.decrypt(doc.getEncryptedKey(), doc.getEncryptionIv(), doc.getKeyVersion());
                        activeRawKeys.remove(decrypted);
                        if (keyManager != null) {
                            keyManager.syncKeys(new ArrayList<>(activeRawKeys));
                        }
                    } catch (Exception e) {
                        log.warn("Could not decrypt key for disable id={}: {}", id, e.getMessage());
                    }
                    log.info("Gemini API key id={} set enabled=false (DISABLED). Active key count: {}", id, activeRawKeys.size());
                    return toDto(doc);
                }

                // Enable requested: Validate exact Gemini key first!
                String plainKey;
                try {
                    plainKey = encryptionService.decrypt(doc.getEncryptedKey(), doc.getEncryptionIv(), doc.getKeyVersion());
                } catch (Exception e) {
                    doc.setActive(false);
                    doc.setStatus(AiApiKeyStatus.INVALID);
                    doc.setLastError("DECRYPTION_FAILED");
                    doc.setLastFailureAt(Instant.now());
                    doc.setUpdatedAt(Instant.now());
                    doc = repository.save(doc);
                    return toDto(doc);
                }

                KeyValidationResult validation = validateKey(plainKey);
                if (validation.success()) {
                    doc.setActive(true);
                    doc.setStatus(AiApiKeyStatus.ACTIVE);
                    doc.setLastUsedAt(Instant.now());
                    doc.setLastError(null);
                    doc.setLastErrorCode(null);
                    doc.setUpdatedAt(Instant.now());
                    doc = repository.save(doc);

                    if (!activeRawKeys.contains(plainKey)) {
                        activeRawKeys.add(plainKey);
                    }
                    if (keyManager != null) {
                        keyManager.syncKeys(new ArrayList<>(activeRawKeys));
                    }
                    log.info("Gemini API key id={} enabled and validated as ACTIVE. Active key count: {}", id, activeRawKeys.size());
                } else if (validation.status() == AiApiKeyStatus.EXHAUSTED) {
                    doc.setActive(false);
                    doc.setStatus(AiApiKeyStatus.EXHAUSTED);
                    doc.setLastErrorCode(429);
                    doc.setLastError("RESOURCE_EXHAUSTED");
                    doc.setLastFailureAt(Instant.now());
                    doc.setUpdatedAt(Instant.now());
                    doc = repository.save(doc);

                    activeRawKeys.remove(plainKey);
                    if (keyManager != null) {
                        keyManager.syncKeys(new ArrayList<>(activeRawKeys));
                    }
                    log.warn("Gemini API key id={} enable attempted but quota is EXHAUSTED.", id);
                } else if (validation.status() == AiApiKeyStatus.INVALID) {
                    doc.setActive(false);
                    doc.setStatus(AiApiKeyStatus.INVALID);
                    doc.setLastErrorCode(validation.errorCode());
                    doc.setLastError(validation.error());
                    doc.setLastFailureAt(Instant.now());
                    doc.setUpdatedAt(Instant.now());
                    doc = repository.save(doc);

                    activeRawKeys.remove(plainKey);
                    if (keyManager != null) {
                        keyManager.syncKeys(new ArrayList<>(activeRawKeys));
                    }
                    log.warn("Gemini API key id={} enable attempted but credential is INVALID ({}).", id, validation.error());
                } else {
                    // Transient error: preserve safe non-active state (keep DISABLED)
                    doc.setActive(false);
                    doc.setStatus(AiApiKeyStatus.DISABLED);
                    doc.setLastErrorCode(validation.errorCode());
                    doc.setLastError(validation.error());
                    doc.setLastFailureAt(Instant.now());
                    doc.setUpdatedAt(Instant.now());
                    doc = repository.save(doc);

                    activeRawKeys.remove(plainKey);
                    if (keyManager != null) {
                        keyManager.syncKeys(new ArrayList<>(activeRawKeys));
                    }
                    log.warn("Gemini API key id={} enable attempted but hit transient error ({}). Kept DISABLED.", id, validation.error());
                }

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

        boolean wasDisabled = (doc.getStatus() == AiApiKeyStatus.DISABLED);
        KeyValidationResult validation = validateKey(plainKey);

        if (wasDisabled) {
            // Keep DISABLED: do not automatically enable a disabled key upon test
            doc.setStatus(AiApiKeyStatus.DISABLED);
            doc.setActive(false);
            if (validation.success()) {
                doc.setLastError(null);
                doc.setLastErrorCode(null);
                doc.setLastUsedAt(Instant.now());
                doc.setUpdatedAt(Instant.now());
                repository.save(doc);
                return new TestAiApiKeyResponse(true, "ACTIVE", "API key is valid and available (currently DISABLED).");
            } else if (validation.status() == AiApiKeyStatus.EXHAUSTED) {
                doc.setLastErrorCode(429);
                doc.setLastError("RESOURCE_EXHAUSTED");
                doc.setLastFailureAt(Instant.now());
                doc.setUpdatedAt(Instant.now());
                repository.save(doc);
                return new TestAiApiKeyResponse(false, "EXHAUSTED", "API key quota is currently exhausted.");
            } else if (validation.status() == AiApiKeyStatus.INVALID) {
                doc.setLastErrorCode(validation.errorCode());
                doc.setLastError(validation.error());
                doc.setLastFailureAt(Instant.now());
                doc.setUpdatedAt(Instant.now());
                repository.save(doc);
                return new TestAiApiKeyResponse(false, "INVALID", "API key is invalid or unauthorized.");
            } else {
                return new TestAiApiKeyResponse(false, "DISABLED", validation.message());
            }
        }

        // Key was NOT disabled (ACTIVE, EXHAUSTED, or INVALID)
        if (validation.success()) {
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
        } else if (validation.status() == AiApiKeyStatus.EXHAUSTED) {
            doc.setStatus(AiApiKeyStatus.EXHAUSTED);
            doc.setActive(false);
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
        } else if (validation.status() == AiApiKeyStatus.INVALID) {
            doc.setStatus(AiApiKeyStatus.INVALID);
            doc.setActive(false);
            doc.setLastErrorCode(validation.errorCode());
            doc.setLastError(validation.error());
            doc.setLastFailureAt(Instant.now());
            doc.setUpdatedAt(Instant.now());
            repository.save(doc);

            activeRawKeys.remove(plainKey);
            if (keyManager != null) {
                keyManager.syncKeys(new ArrayList<>(activeRawKeys));
            }
            return new TestAiApiKeyResponse(false, "INVALID", "API key is invalid or unauthorized.");
        } else {
            return new TestAiApiKeyResponse(false, doc.getStatus().name(), validation.message());
        }
    }

    public com.apms.domain.ai.dto.RevealAiApiKeyResponse revealKey(String id) {
        if (repository == null || id == null) {
            throw new ResourceNotFoundException("API key storage not available");
        }
        AiApiKeyDocument doc = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("API key not found with id: " + id));
        String plainKey = encryptionService.decrypt(doc.getEncryptedKey(), doc.getEncryptionIv(), doc.getKeyVersion());
        return new com.apms.domain.ai.dto.RevealAiApiKeyResponse(doc.getId(), plainKey);
    }

    public synchronized void recordKeySuccess(String rawKey) {
        if (rawKey == null) return;
        String docId = rawKeyToDocId.get(rawKey);
        if (docId != null && repository != null) {
            repository.findById(docId).ifPresent(doc -> {
                doc.setLastUsedAt(Instant.now());
                if (doc.getStatus() != AiApiKeyStatus.DISABLED) {
                    doc.setStatus(AiApiKeyStatus.ACTIVE);
                    doc.setActive(true);
                    doc.setLastError(null);
                    doc.setLastErrorCode(null);
                }
                doc.setUpdatedAt(Instant.now());
                repository.save(doc);
            });
        }
    }

    public synchronized void recordKeyFailure(String rawKey, int errorCode, String errorMessage, AiApiKeyStatus targetStatus) {
        if (rawKey == null) return;
        activeRawKeys.remove(rawKey);
        String docId = rawKeyToDocId.get(rawKey);
        if (docId != null && repository != null) {
            repository.findById(docId).ifPresent(doc -> {
                doc.setStatus(targetStatus);
                doc.setActive(false);
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
        AiApiKeyStatus effectiveStatus = doc.getStatus() != null
                ? doc.getStatus()
                : (doc.isActive() ? AiApiKeyStatus.ACTIVE : AiApiKeyStatus.DISABLED);
        Integer errorCode = effectiveStatus == AiApiKeyStatus.ACTIVE ? null : doc.getLastErrorCode();
        String errorMsg = effectiveStatus == AiApiKeyStatus.ACTIVE ? null : doc.getLastError();

        return AiApiKeyDto.builder()
                .id(doc.getId())
                .provider(doc.getProvider() != null ? doc.getProvider() : "GEMINI")
                .maskedKey(doc.getMaskedKey())
                .label(doc.getLabel())
                .status(effectiveStatus)
                .lastUsedAt(doc.getLastUsedAt())
                .lastFailureAt(doc.getLastFailureAt())
                .lastErrorCode(errorCode)
                .lastError(errorMsg)
                .active(doc.isActive())
                .orderIndex(doc.getOrderIndex())
                .createdSource(doc.getCreatedSource())
                .createdAt(doc.getCreatedAt())
                .updatedAt(doc.getUpdatedAt())
                .build();
    }
}
