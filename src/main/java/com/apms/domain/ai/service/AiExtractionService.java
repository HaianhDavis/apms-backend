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
import com.apms.domain.project.Project;
import com.apms.domain.project.repository.sql.ProjectRepository;
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
    private final ProjectRepository projectRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.ai.provider:gemini}")
    private String aiProvider;

    @Value("${app.ai.gemini.api-key:dummy-key}")
    private String geminiApiKey;

    @Value("${app.ai.gemini.api-keys:}")
    private String geminiApiKeys;

    @Value("${spring.ai.openai.api-key:dummy-key}")
    private String openAiApiKey;

    @Value("${app.ai.gemini.model:gemini-3.6-flash}")
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
                               ProjectRepository projectRepository,
                               ObjectMapper objectMapper) {
        this.importJobRepository = importJobRepository;
        this.rawDocumentRepository = rawDocumentRepository;
        this.extractionCacheRepository = extractionCacheRepository;
        this.mockProvider = mockProvider;
        this.geminiProvider = geminiProvider;
        this.openAiProvider = openAiProvider;
        this.qualityService = qualityService;
        this.projectRepository = projectRepository;
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

        com.apms.domain.ai.dto.RawExtractionOutput rawOutputObj;
        String usedProvider;
        String usedModel;

        if (useMock) {
            log.info("Mocking AI extraction (provider='{}', mock-detected) for ImportJob {}", aiProvider, importJobId);
            rawOutputObj = mockProvider.extract("");
            usedProvider = "mock";
            usedModel = "mock";
        } else {
            String sourceText = extractTextFromDocument(rawDocument);
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

        Project project = null;
        if (importJob.getProjectId() != null) {
            project = projectRepository.findById(importJob.getProjectId()).orElse(null);
        }
        applyProjectControlledIdentity(rawOutputObj, project);
        removeAnalysisExtractionFields(rawOutputObj);

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

        if (request.isManager()) {
            if (request.getManagerReviewStatus() != null) {
                fieldResult.setManagerReviewStatus(request.getManagerReviewStatus());
            }
            if (request.getReviewedValue() != null || request.getManagerReviewStatus() == com.apms.domain.ai.dto.ExtractionReviewStatus.EDITED) {
                if (request.getManagerReviewStatus() == com.apms.domain.ai.dto.ExtractionReviewStatus.EDITED && request.getReviewedValue() == null) {
                    throw new BusinessValidationException("EDITED review status requires a reviewedValue.");
                }
                fieldResult.setStaffReviewedValue(request.getReviewedValue());
            } else {
                fieldResult.setStaffReviewedValue(request.getReviewedValue());
            }
            fieldResult.setManagerReviewComment(request.getComment());
            fieldResult.setManagerReviewedByUserId(userId);
            fieldResult.setManagerReviewedAt(LocalDateTime.now());
        } else {
            if (fieldResult.getManagerReviewStatus() == com.apms.domain.ai.dto.ExtractionReviewStatus.ACCEPTED) {
                throw new BusinessValidationException("Cannot edit field because manager has already ACCEPTED it.");
            }
            if (request.getStaffReviewStatus() != null) {
                fieldResult.setStaffReviewStatus(request.getStaffReviewStatus());
            }
            if (request.getReviewedValue() != null || request.getStaffReviewStatus() == com.apms.domain.ai.dto.StaffFieldReviewStatus.EDITED) {
                if (request.getStaffReviewStatus() == com.apms.domain.ai.dto.StaffFieldReviewStatus.EDITED && request.getReviewedValue() == null) {
                    throw new BusinessValidationException("EDITED review status requires a reviewedValue.");
                }
                fieldResult.setStaffReviewedValue(request.getReviewedValue());
            } else {
                fieldResult.setStaffReviewedValue(request.getReviewedValue());
            }
            
            if ((fieldResult.getManagerReviewStatus() == com.apms.domain.ai.dto.ExtractionReviewStatus.REJECTED || 
                 fieldResult.getManagerReviewStatus() == com.apms.domain.ai.dto.ExtractionReviewStatus.NEEDS_REVIEW) &&
                request.getStaffReviewStatus() == com.apms.domain.ai.dto.StaffFieldReviewStatus.EDITED) {
                fieldResult.setManagerReviewStatus(com.apms.domain.ai.dto.ExtractionReviewStatus.PENDING);
            }

            fieldResult.setStaffReviewComment(request.getComment());
            fieldResult.setStaffReviewedByUserId(userId);
            fieldResult.setStaffReviewedAt(LocalDateTime.now());
        }

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
                    if (result.getManagerReviewStatus() == com.apms.domain.ai.dto.ExtractionReviewStatus.NEEDS_REVIEW ||
                       (result.getManagerReviewStatus() == com.apms.domain.ai.dto.ExtractionReviewStatus.PENDING &&
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
            return !hasRealGeminiCredential();
        }
        if ("openai".equalsIgnoreCase(aiProvider)) {
            return "dummy-key".equals(openAiApiKey) || !StringUtils.hasText(openAiApiKey);
        }
        return true;
    }

    private boolean hasRealGeminiCredential() {
        if (StringUtils.hasText(geminiApiKeys)) {
            return java.util.Arrays.stream(geminiApiKeys.split(","))
                    .map(String::trim)
                    .anyMatch(key -> StringUtils.hasText(key) && !"dummy-key".equals(key));
        }
        return StringUtils.hasText(geminiApiKey) && !"dummy-key".equals(geminiApiKey);
    }

    private void applyProjectControlledIdentity(com.apms.domain.ai.dto.RawExtractionOutput output, Project project) {
        if (output == null || project == null) return;

        if (output.getExtractedData() == null) {
            output.setExtractedData(new ExtractedCompanyData());
        }
        output.getExtractedData().setLegalName(project.getTargetCompanyName());
        output.getExtractedData().setTaxCode(project.getTargetCompanyTaxCode());

        if (output.getFieldResults() == null) {
            output.setFieldResults(new java.util.HashMap<>());
        }
        output.getFieldResults().put("legalName", projectControlledField("legalName", project.getTargetCompanyName()));
        output.getFieldResults().put("taxCode", projectControlledField("taxCode", project.getTargetCompanyTaxCode()));
    }

    private com.apms.domain.ai.dto.ExtractionFieldResult projectControlledField(String fieldName, Object value) {
        return com.apms.domain.ai.dto.ExtractionFieldResult.builder()
                .fieldName(fieldName)
                .value(value)
                .normalizedValue(value)
                .confidence(1.0)
                .validationStatus(com.apms.domain.ai.dto.ExtractionValidationStatus.PASS)
                .validationMessages("Provided by manager at project creation.")
                .staffReviewStatus(com.apms.domain.ai.dto.StaffFieldReviewStatus.CONFIRMED)
                .staffReviewedValue(value)
                .managerReviewStatus(com.apms.domain.ai.dto.ExtractionReviewStatus.ACCEPTED)
                .build();
    }

    private void removeAnalysisExtractionFields(com.apms.domain.ai.dto.RawExtractionOutput output) {
        if (output == null) return;

        if (output.getExtractedData() != null) {
            output.getExtractedData().setStrengths(null);
            output.getExtractedData().setWeaknesses(null);
            output.getExtractedData().setOpportunities(null);
            output.getExtractedData().setThreats(null);
            output.getExtractedData().setFinancial(null);
            output.getExtractedData().setInnovation(null);
            output.getExtractedData().setMarket(null);
            output.getExtractedData().setRisk(null);
            output.getExtractedData().setCompliance(null);
        }

        if (output.getFieldResults() != null) {
            for (String field : java.util.List.of(
                    "strengths", "weaknesses", "opportunities", "threats",
                    "financial", "innovation", "market", "risk", "compliance")) {
                output.getFieldResults().remove(field);
            }
        }
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
