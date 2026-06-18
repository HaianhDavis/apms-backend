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
                               ObjectMapper objectMapper) {
        this.importJobRepository = importJobRepository;
        this.rawDocumentRepository = rawDocumentRepository;
        this.extractionCacheRepository = extractionCacheRepository;
        this.mockProvider = mockProvider;
        this.geminiProvider = geminiProvider;
        this.openAiProvider = openAiProvider;
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
        if (!StringUtils.hasText(rawDocumentId)) {
            throw new ResourceNotFoundException("No RawDocument linked to ImportJob id: " + importJobId);
        }

        RawDocument rawDocument = rawDocumentRepository.findById(rawDocumentId)
                .orElseThrow(() -> new ResourceNotFoundException("RawDocument not found with id: " + rawDocumentId));

        // Determine if mock or real
        boolean useMock = isMockMode();

        ExtractedCompanyData extractedData;
        String usedProvider;
        String usedModel;

        if (useMock) {
            log.info("Mocking AI extraction (provider='{}', mock-detected) for ImportJob {}", aiProvider, importJobId);
            extractedData = mockProvider.extract("");
            usedProvider = "mock";
            usedModel = "mock";
        } else {
            String sourceText = extractTextFromDocument(rawDocument);
            log.info("Calling AI provider '{}' for ImportJob {}, text length: {}", aiProvider, importJobId, sourceText.length());

            if ("openai".equalsIgnoreCase(aiProvider)) {
                extractedData = openAiProvider.extract(sourceText);
                usedProvider = "openai";
                usedModel = "gpt-4";
            } else {
                extractedData = geminiProvider.extract(sourceText);
                usedProvider = "gemini";
                usedModel = geminiModel;
            }
        }

        // Serialize for raw output field
        String rawOutput = serializeToJson(extractedData);

        // Persist to cache (overwrite any prior entry for this importJobId)
        saveToCache(importJobId, rawDocumentId, usedProvider, usedModel, extractedData, rawOutput);

        return AiExtractionResult.builder()
                .importJobId(importJobId)
                .rawDocumentId(rawDocumentId)
                .extractedData(extractedData)
                .rawAiOutput(rawOutput)
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
        return extractionCacheRepository.findTopByImportJobIdOrderByCreatedAtDesc(importJobId)
                .orElseThrow(() -> new ResourceNotFoundException("No AI extraction found for ImportJob: " + importJobId));
    }

    @Transactional(readOnly = true)
    public AiExtractionCache getExtractionById(String extractionId) {
        return extractionCacheRepository.findById(extractionId)
                .orElseThrow(() -> new ResourceNotFoundException("AI extraction not found: " + extractionId));
    }

    @Transactional
    public AiExtractionCache updateExtraction(String extractionId, ExtractedCompanyData editedData, Long userId) {
        AiExtractionCache cache = getExtractionById(extractionId);

        // Update ONLY the extracted data and metadata, preserve all immutable properties
        cache.setExtractedData(editedData);
        cache.setLastModifiedBy(String.valueOf(userId));
        cache.setUpdatedAt(LocalDateTime.now());

        AiExtractionCache saved = extractionCacheRepository.save(cache);
        log.info("AI extraction updated manually: extractionId={}, userId={}", extractionId, userId);
        return saved;
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
                             ExtractedCompanyData extractedData, String rawOutput) {
        try {
            AiExtractionCache cache = AiExtractionCache.builder()
                    .importJobId(importJobId)
                    .rawDocumentId(rawDocumentId)
                    .provider(provider)
                    .model(model)
                    .extractedData(extractedData)
                    .rawAiOutput(rawOutput)
                    .createdAt(LocalDateTime.now())
                    .build();
            extractionCacheRepository.save(cache);
            log.info("Saved AI extraction cache for ImportJob {} (provider={})", importJobId, provider);
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
