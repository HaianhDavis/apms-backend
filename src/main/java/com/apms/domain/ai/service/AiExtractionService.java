package com.apms.domain.ai.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.ai.AiExtractionCache;
import com.apms.domain.ai.dto.AiExtractionResult;
import com.apms.domain.ai.dto.ExtractedCompanyData;
import com.apms.domain.ai.repository.mongo.AiExtractionCacheRepository;
import com.apms.domain.ai.service.provider.GeminiExtractionProvider;
import com.apms.domain.ai.service.provider.MockExtractionProvider;
import com.apms.domain.ai.service.provider.OpenAiExtractionProvider;
import com.apms.domain.document.ImportJob;
import com.apms.domain.document.RawDocument;
import com.apms.domain.document.repository.mongo.RawDocumentRepository;
import com.apms.domain.document.repository.sql.ImportJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class AiExtractionService {

    private final ImportJobRepository importJobRepository;
    private final RawDocumentRepository rawDocumentRepository;
    private final AiExtractionCacheRepository extractionCacheRepository;
    private final MockExtractionProvider mockProvider;
    private final GeminiExtractionProvider geminiProvider;
    private final OpenAiExtractionProvider openAiProvider;
    private final AiExtractionQualityService qualityService;
    private final ObjectMapper objectMapper;

    @Value("${app.ai.provider:gemini}")
    private String aiProvider;

    @Value("${app.ai.gemini.api-key:dummy-key}")
    private String geminiApiKey;

    @Value("${spring.ai.openai.api-key:dummy-key}")
    private String openAiApiKey;

    @Value("${app.ai.gemini.model:gemini-2.5-flash}")
    private String geminiModel;

    @Value("${app.storage.upload-dir:uploads/}")
    private String uploadDir;

    public AiExtractionService(ImportJobRepository importJobRepository,
                               RawDocumentRepository rawDocumentRepository,
                               AiExtractionCacheRepository extractionCacheRepository,
                               MockExtractionProvider mockProvider,
                               GeminiExtractionProvider geminiProvider,
                               OpenAiExtractionProvider openAiProvider,
                               AiExtractionQualityService qualityService,
                               ObjectMapper objectMapper) {
        this.importJobRepository = importJobRepository;
        this.rawDocumentRepository = rawDocumentRepository;
        this.extractionCacheRepository = extractionCacheRepository;
        this.mockProvider = mockProvider;
        this.geminiProvider = geminiProvider;
        this.openAiProvider = openAiProvider;
        this.qualityService = qualityService;
        this.objectMapper = objectMapper;
    }

    // ─────────────────────────────────────────────
    // PUBLIC: Called by AiController — always runs fresh, saves to cache
    // ─────────────────────────────────────────────

    @Transactional
    public AiExtractionResult extractCompanyData(Long importJobId) {
        ImportJob importJob = importJobRepository.findById(importJobId)
                .orElseThrow(() -> new ResourceNotFoundException("ImportJob not found with id: " + importJobId));

        String rawDocumentId = importJob.getRawDocumentId();
        if (!StringUtils.hasText(rawDocumentId) && !StringUtils.hasText(importJob.getLocalFilePath())) {
            throw new ResourceNotFoundException("No RawDocument and no Local File linked to ImportJob id: " + importJobId);
        }

        // Determine if mock or real
        boolean useMock = isMockMode();

        com.apms.domain.ai.dto.RawExtractionOutput rawOutputObj;
        String usedProvider;
        String usedModel;

        if (useMock) {
            log.info("Mocking AI extraction (provider='{}', mock-detected) for ImportJob {}", aiProvider, importJobId);
            rawOutputObj = mockProvider.extract("");
            usedProvider = "mock";
            usedModel = "mock";
        } else {
            String sourceText = null;
            if (StringUtils.hasText(rawDocumentId)) {
                try {
                    RawDocument rawDocument = rawDocumentRepository.findById(rawDocumentId)
                        .orElseThrow(() -> new ResourceNotFoundException("RawDocument not found with id: " + rawDocumentId));
                    sourceText = extractTextFromDocument(rawDocument);
                } catch (Exception e) {
                    log.warn("RawDocument not found or error extracting text. Falling back to ImportJob local file path.", e);
                }
            }
            
            if (sourceText == null) {
                if (StringUtils.hasText(importJob.getLocalFilePath())) {
                    try {
                        String localPath = importJob.getLocalFilePath();
                        if (!localPath.contains("/") && !localPath.contains("\\")) {
                            localPath = "uploads/" + localPath;
                        }
                        sourceText = java.nio.file.Files.readString(java.nio.file.Paths.get(localPath));
                    } catch (Exception ex) {
                        throw new BusinessValidationException("Failed to read text from fallback local file: " + importJob.getLocalFilePath());
                    }
                } else {
                    throw new BusinessValidationException("Cannot extract text: RawDocument missing and no local file path.");
                }
            }

            log.info("Calling AI provider '{}' for ImportJob {}, text length: {}", aiProvider, importJobId, sourceText.length());

            if ("openai".equalsIgnoreCase(aiProvider)) {
                rawOutputObj = openAiProvider.extract(sourceText);
                usedProvider = "openai";
                usedModel = "gpt-4";
            } else {
                rawOutputObj = geminiProvider.extract(sourceText);
                usedProvider = "gemini";
                usedModel = geminiModel;
            }
        }

        // Apply Quality Validation
        qualityService.validateExtraction(rawOutputObj.getFieldResults());
        com.apms.domain.ai.dto.ExtractionQualityMetrics metrics = qualityService.computeMetrics(rawOutputObj.getFieldResults());
        com.apms.domain.ai.dto.ExtractionQualityStatus status = qualityService.determineOverallStatus(metrics);

        // Persist to cache (overwrite any prior entry for this importJobId)
        saveToCache(importJobId, rawDocumentId, usedProvider, usedModel, rawOutputObj.getExtractedData(), rawOutputObj.getRawAiOutputString(), rawOutputObj.getFieldResults(), metrics, status);

        return AiExtractionResult.builder()
                .importJobId(importJobId)
                .rawDocumentId(rawDocumentId)
                .extractedData(rawOutputObj.getExtractedData())
                .rawAiOutput(rawOutputObj.getRawAiOutputString())
                .build();
    }

    // ─────────────────────────────────────────────
    // PUBLIC: Called by CandidateService — loads from cache, never calls AI again
    // ─────────────────────────────────────────────

    public AiExtractionResult getOrExtractCompanyData(Long importJobId) {
        // Try cache first
        Optional<AiExtractionCache> cached = extractionCacheRepository
                .findTopByImportJobIdOrderByCreatedAtDesc(importJobId);

        if (cached.isPresent()) {
            AiExtractionCache entry = cached.get();
            log.info("Loaded cached AI extraction for ImportJob {} (provider={}, createdAt={})",
                    importJobId, entry.getProvider(), entry.getCreatedAt());
            return AiExtractionResult.builder()
                    .importJobId(importJobId)
                    .rawDocumentId(entry.getRawDocumentId())
                    .extractedData(entry.getExtractedData())
                    .rawAiOutput(entry.getRawAiOutput())
                    .build();
        }

        // No cache → run extraction once and it will save to cache automatically
        log.info("No cached extraction found for ImportJob {}. Running extraction now.", importJobId);
        return extractCompanyData(importJobId);
    }

    // ─────────────────────────────────────────────
    // PUBLIC: EXTRACTION CACHE MANAGEMENT (MANUAL REVIEW)
    // ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    public AiExtractionCache getLatestExtraction(Long importJobId) {
        try {
            return extractionCacheRepository.findTopByImportJobIdOrderByCreatedAtDesc(importJobId)
                    .orElseThrow(() -> new ResourceNotFoundException("No AI extraction found for ImportJob: " + importJobId));
        } catch (ResourceNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.warn("MongoDB error when fetching extraction for ImportJob {}", importJobId, e);
            throw new ResourceNotFoundException("No AI extraction found for ImportJob: " + importJobId);
        }
    }

    @Transactional(readOnly = true)
    public AiExtractionCache getExtractionById(String extractionId) {
        try {
            return extractionCacheRepository.findById(extractionId)
                    .orElseThrow(() -> new ResourceNotFoundException("AI extraction not found: " + extractionId));
        } catch (ResourceNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.warn("MongoDB error when fetching extraction {}", extractionId, e);
            throw new ResourceNotFoundException("AI extraction not found: " + extractionId);
        }
    }

    @Transactional
    public AiExtractionCache updateExtraction(String extractionId, ExtractedCompanyData editedData, Long userId) {
        AiExtractionCache cache = getExtractionById(extractionId);

        // Update ONLY the extracted data and metadata, preserve all immutable properties
        cache.setExtractedData(editedData);
        cache.setLastModifiedBy(String.valueOf(userId));
        cache.setUpdatedAt(LocalDateTime.now());

        try {
            AiExtractionCache saved = extractionCacheRepository.save(cache);
            log.info("AI extraction updated manually: extractionId={}, userId={}", extractionId, userId);
            return saved;
        } catch (Exception e) {
            log.warn("MongoDB error when saving updated extraction {}", extractionId, e);
            return cache; // Return the memory-updated cache even if save fails
        }
    }

    @Transactional
    public AiExtractionCache reviewField(String extractionId, String fieldName, com.apms.domain.ai.dto.ExtractionReviewRequest request, Long userId) {
        AiExtractionCache cache = getExtractionById(extractionId);

        if (cache.getFieldResults() == null) {
            cache.setFieldResults(new java.util.HashMap<>());
        }

        com.apms.domain.ai.dto.ExtractionFieldResult fieldResult = cache.getFieldResults().get(fieldName);
        if (fieldResult == null) {
            fieldResult = com.apms.domain.ai.dto.ExtractionFieldResult.builder().fieldName(fieldName).build();
            cache.getFieldResults().put(fieldName, fieldResult);
        }

        fieldResult.setReviewStatus(request.getReviewStatus());
        if (request.getReviewStatus() == com.apms.domain.ai.dto.ExtractionReviewStatus.EDITED) {
            if (request.getReviewedValue() == null) {
                throw new BusinessValidationException("EDITED review status requires a reviewedValue.");
            }
            fieldResult.setReviewedValue(request.getReviewedValue());
        } else {
            // ACCEPTED, REJECTED, NEEDS_REVIEW
            fieldResult.setReviewedValue(request.getReviewedValue());
        }

        fieldResult.setReviewComment(request.getComment());
        fieldResult.setReviewedByUserId(userId);
        fieldResult.setReviewedAt(LocalDateTime.now());

        cache.setLastModifiedBy(String.valueOf(userId));
        cache.setUpdatedAt(LocalDateTime.now());

        return extractionCacheRepository.save(cache);
    }

    @Transactional
    public AiExtractionCache completeReview(String extractionId, Long userId) {
        AiExtractionCache cache = getExtractionById(extractionId);

        if (cache.getFieldResults() != null) {
            // Validate that no critical fields are NEEDS_REVIEW or FAILED and unreviewed
            for (Map.Entry<String, com.apms.domain.ai.dto.ExtractionFieldResult> entry : cache.getFieldResults().entrySet()) {
                com.apms.domain.ai.dto.ExtractionFieldResult result = entry.getValue();
                if ("legalName".equals(entry.getKey()) || "taxCode".equals(entry.getKey())) {
                    if (result.getReviewStatus() == com.apms.domain.ai.dto.ExtractionReviewStatus.NEEDS_REVIEW ||
                       (result.getReviewStatus() == com.apms.domain.ai.dto.ExtractionReviewStatus.PENDING &&
                        result.getValidationStatus() == com.apms.domain.ai.dto.ExtractionValidationStatus.FAIL)) {
                        throw new BusinessValidationException("Cannot complete review. Critical field '" + entry.getKey() + "' requires review.");
                    }
                }
            }
        }

        cache.setQualityStatus(com.apms.domain.ai.dto.ExtractionQualityStatus.REVIEWED);
        cache.setReviewedByUserId(userId);
        cache.setReviewedAt(LocalDateTime.now());
        cache.setLastModifiedBy(String.valueOf(userId));
        cache.setUpdatedAt(LocalDateTime.now());

        return extractionCacheRepository.save(cache);
    }

    // ─────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────

    private boolean isMockMode() {
        if ("mock".equalsIgnoreCase(aiProvider)) return true;
        if ("gemini".equalsIgnoreCase(aiProvider)) {
            return "dummy-key".equals(geminiApiKey) || !StringUtils.hasText(geminiApiKey);
        }
        if ("openai".equalsIgnoreCase(aiProvider)) {
            return "dummy-key".equals(openAiApiKey) || !StringUtils.hasText(openAiApiKey);
        }
        return true;
    }

    private void saveToCache(Long importJobId, String rawDocumentId,
                             String provider, String model,
                             ExtractedCompanyData extractedData, String rawOutput,
                             java.util.Map<String, com.apms.domain.ai.dto.ExtractionFieldResult> fieldResults,
                             com.apms.domain.ai.dto.ExtractionQualityMetrics qualityMetrics,
                             com.apms.domain.ai.dto.ExtractionQualityStatus qualityStatus) {
        try {
            AiExtractionCache cache = AiExtractionCache.builder()
                    .importJobId(importJobId)
                    .rawDocumentId(rawDocumentId)
                    .provider(provider)
                    .model(model)
                    .extractedData(extractedData)
                    .rawAiOutput(rawOutput)
                    .fieldResults(fieldResults)
                    .qualityMetrics(qualityMetrics)
                    .qualityStatus(qualityStatus != null ? qualityStatus : com.apms.domain.ai.dto.ExtractionQualityStatus.PENDING_VALIDATION)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();

            try {
                extractionCacheRepository.save(cache);
                log.info("Saved new AI extraction for ImportJob {} to MongoDB", importJobId);
            } catch (Exception e) {
                log.error("Failed to save AI extraction to MongoDB for ImportJob {}. It will not be cached.", importJobId, e);
            }
        } catch (Exception e) {
            log.warn("Failed to save AI extraction cache for ImportJob {}. Continuing.", importJobId, e);
        }
    }

    private String serializeToJson(ExtractedCompanyData data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            log.warn("Failed to serialize extracted data to JSON.");
            return "{}";
        }
    }

    private String extractTextFromDocument(RawDocument rawDocument) {
        if (rawDocument.getSource() == null || rawDocument.getSource().getType() == null) {
            return "";
        }

        String type = rawDocument.getSource().getType().toUpperCase();

        return switch (type) {
            case "MANUAL_INPUT" -> rawDocument.getSource().getInputText() != null
                    ? rawDocument.getSource().getInputText() : "";
            case "PDF" -> extractFromPdf(rawDocument);
            case "CSV", "TXT", "OTHER" -> extractFromPlainText(rawDocument);
            case "DOCX", "XLSX" -> throw new BusinessValidationException(
                    "File type " + type + " is not supported for AI extraction yet.");
            default -> throw new BusinessValidationException("Unsupported document type: " + type);
        };
    }

    private String extractFromPdf(RawDocument rawDocument) {
        String path = getStoragePath(rawDocument);
        try (PDDocument document = Loader.loadPDF(new File(path))) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        } catch (Exception e) {
            log.error("Failed to read PDF file at {}", path, e);
            throw new BusinessValidationException("Failed to read PDF content.");
        }
    }

    private String extractFromPlainText(RawDocument rawDocument) {
        String path = getStoragePath(rawDocument);
        try {
            return Files.readString(Paths.get(path));
        } catch (Exception e) {
            log.error("Failed to read plain text file at {}", path, e);
            throw new BusinessValidationException("Failed to read text content.");
        }
    }

    private String getStoragePath(RawDocument rawDocument) {
        if (rawDocument.getStorage() == null || !StringUtils.hasText(rawDocument.getStorage().getPath())) {
            throw new BusinessValidationException("Document storage path is missing.");
        }

        String storedPath = rawDocument.getStorage().getPath();
        File file = new File(storedPath);

        // 1. Try path as-is
        if (file.exists() && file.isFile()) {
            return storedPath;
        }

        // 2. Try resolving against app.storage.upload-dir
        File resolvedFile = Paths.get(uploadDir).resolve(storedPath).toFile();
        if (resolvedFile.exists() && resolvedFile.isFile()) {
            return resolvedFile.getAbsolutePath();
        }

        // 3. Fail with clear message
        throw new BusinessValidationException("Uploaded file not found at path: " + storedPath);
    }
}
