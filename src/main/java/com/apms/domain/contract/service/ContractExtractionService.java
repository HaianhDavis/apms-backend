package com.apms.domain.contract.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.contract.dto.ai.AiContractClassificationCandidate;
import com.apms.domain.contract.dto.ai.AiContractExtractionCandidate;
import com.apms.domain.contract.enums.ContractType;
import com.apms.domain.document.RawDocument;
import com.apms.domain.document.service.StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class ContractExtractionService {

    private final ObjectMapper objectMapper;
    private final RestClient.Builder restClientBuilder;

    @Autowired(required = false)
    private StorageService storageService;

    @Value("${app.ai.gemini.api-key:dummy-key}")
    private String geminiApiKey;

    @Value("${app.ai.gemini.model:gemini-3.6-flash}")
    private String geminiModel;

    @Value("${app.storage.upload-dir:uploads/}")
    private String storagePath;

    private static final int MAX_GEMINI_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 2000L;

    /**
     * Resolve the physical PDF File from various path strategies.
     */
    public File resolvePdfFile(RawDocument doc) {
        if (doc == null) return null;
        String docPath = doc.getStorage() != null ? doc.getStorage().getPath() : null;
        if (!StringUtils.hasText(docPath)) return null;

        // 1. Check direct path as-is
        File file = new File(docPath);
        if (file.exists() && file.isFile()) {
            return file;
        }

        // 2. Check relative to storagePath
        if (StringUtils.hasText(storagePath)) {
            File sFile = new File(storagePath, docPath);
            if (sFile.exists() && sFile.isFile()) {
                return sFile;
            }

            // If docPath starts with "uploads/" or "uploads\"
            if (docPath.startsWith("uploads/") || docPath.startsWith("uploads\\")) {
                String sub = docPath.substring(8);
                File subFile = new File(storagePath, sub);
                if (subFile.exists() && subFile.isFile()) {
                    return subFile;
                }
            }

            // Path normalize
            try {
                Path p = Paths.get(storagePath).resolve(docPath).normalize();
                if (Files.exists(p) && Files.isRegularFile(p)) {
                    return p.toFile();
                }
            } catch (Exception ignored) {}
        }

        // 3. StorageService loader if injected
        try {
            if (storageService != null) {
                Path p = storageService.load(docPath);
                if (p != null && Files.exists(p) && Files.isRegularFile(p)) {
                    return p.toFile();
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    /**
     * Stage 1: Contract Classification & Context Analysis
     */
    public AiContractClassificationCandidate classifyContract(RawDocument doc) {
        String promptTemplate = loadPrompt("ai-prompts/contract-classification.prompt.md");

        BusinessValidationException lastAiException = null;

        // Try Multimodal API first if PDF file is available on disk
        File pdfFile = resolvePdfFile(doc);
        if (pdfFile != null && pdfFile.getName().toLowerCase().endsWith(".pdf")) {
            try {
                byte[] fileBytes = Files.readAllBytes(pdfFile.toPath());
                if (fileBytes.length <= 20 * 1024 * 1024) { // Under 20MB
                    String base64 = Base64.getEncoder().encodeToString(fileBytes);
                    log.info("Sending contract PDF to Gemini Multimodal for classification: {}", pdfFile.getName());
                    String responseJson = callGeminiMultimodal(promptTemplate, "application/pdf", base64);
                    if (responseJson != null) {
                        return parseResponse(responseJson, AiContractClassificationCandidate.class);
                    }
                }
            } catch (BusinessValidationException bve) {
                lastAiException = bve;
                log.warn("Multimodal classification failed with AI exception: {}", bve.getMessage());
            } catch (Exception e) {
                log.warn("Multimodal classification failed, falling back to text stripper", e);
            }
        }

        // Fallback to text parsing
        String docText = parseDocumentText(doc);
        if (docText.isBlank()) {
            if (lastAiException != null) {
                throw lastAiException;
            }
            throw new BusinessValidationException("EMPTY_DOCUMENT", "Tài liệu không có lớp văn bản (text layer) và hệ thống OCR chưa đọc được. Vui lòng thử lại hoặc tải lên tài liệu rõ ràng hơn.");
        }

        String fullPrompt = promptTemplate + "\n\n=== DOCUMENT TEXT ===\n" + docText;
        String responseJson = callGemini(fullPrompt);
        return parseResponse(responseJson, AiContractClassificationCandidate.class);
    }

    /**
     * Stage 2: Structured Common Contract Data Extraction
     */
    public AiContractExtractionCandidate extractStructuredContract(RawDocument doc, ContractType confirmedType) {
        String promptTemplate = loadPrompt("ai-prompts/contract-extraction.prompt.md");
        BusinessValidationException lastAiException = null;

        // Try Multimodal API first if PDF file is available on disk
        File pdfFile = resolvePdfFile(doc);
        if (pdfFile != null && pdfFile.getName().toLowerCase().endsWith(".pdf")) {
            try {
                byte[] fileBytes = Files.readAllBytes(pdfFile.toPath());
                if (fileBytes.length <= 20 * 1024 * 1024) { // Under 20MB
                    String base64 = Base64.getEncoder().encodeToString(fileBytes);
                    log.info("Sending contract PDF to Gemini Multimodal for extraction: {}", pdfFile.getName());
                    String responseJson = callGeminiMultimodal(promptTemplate, "application/pdf", base64);
                    if (responseJson != null) {
                        return parseResponse(responseJson, AiContractExtractionCandidate.class);
                    }
                }
            } catch (BusinessValidationException bve) {
                lastAiException = bve;
                log.warn("Multimodal extraction failed with AI exception: {}", bve.getMessage());
            } catch (Exception e) {
                log.warn("Multimodal extraction failed, falling back to text stripper", e);
            }
        }

        // Fallback to text parsing
        String docText = parseDocumentText(doc);
        if (docText.isBlank()) {
            if (lastAiException != null) {
                throw lastAiException;
            }
            throw new BusinessValidationException("EMPTY_DOCUMENT", "Tài liệu không có lớp văn bản (text layer) và hệ thống OCR chưa đọc được. Vui lòng thử lại hoặc tải lên tài liệu rõ ràng hơn.");
        }

        String fullPrompt = promptTemplate + "\n\n=== DOCUMENT TEXT ===\n" + docText;
        String responseJson = callGemini(fullPrompt);
        return parseResponse(responseJson, AiContractExtractionCandidate.class);
    }

    public int getDocumentPageCount(RawDocument doc) {
        File file = resolvePdfFile(doc);
        if (file != null && file.getName().toLowerCase().endsWith(".pdf")) {
            try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(file)) {
                return document.getNumberOfPages();
            } catch (Exception e) {
                log.warn("Failed to read page count for doc {}", doc.getId(), e);
            }
        }
        return 1;
    }

    public String parseDocumentText(RawDocument doc) {
        String cachedText = doc.getSource() != null ? doc.getSource().getInputText() : null;
        String sourceType = doc.getSource() != null ? doc.getSource().getType() : null;
        if (cachedText != null && !cachedText.isBlank() && !"PDF".equalsIgnoreCase(sourceType)) {
            return "=== PAGE 1 ===\n" + cachedText;
        }

        File file = resolvePdfFile(doc);
        if (file == null || !file.exists()) {
            if (cachedText != null && !cachedText.isBlank()) {
                return "=== PAGE 1 ===\n" + cachedText;
            }
            throw new BusinessValidationException("FILE_NOT_FOUND", "Cannot extract: PDF file not found on server.");
        }

        StringBuilder sb = new StringBuilder();
        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            int pages = document.getNumberOfPages();
            for (int i = 1; i <= pages; i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String text = stripper.getText(document);
                if (!text.trim().isEmpty()) {
                    sb.append("=== PAGE ").append(i).append(" ===\n");
                    sb.append(text).append("\n");
                }
            }
        } catch (IOException e) {
            log.error("Failed to parse PDF document {}", file.getAbsolutePath(), e);
            if (cachedText != null && !cachedText.isBlank()) {
                return "=== PAGE 1 ===\n" + cachedText;
            }
            throw new BusinessValidationException("PDF_PARSE_ERROR", "Failed to read PDF file: " + e.getMessage());
        }

        String result = sb.toString();
        if (result.isBlank() && cachedText != null && !cachedText.isBlank()) {
            return "=== PAGE 1 ===\n" + cachedText;
        }
        return result;
    }

    private String getSubtypeSchemaSpecification(ContractType type) {
        return switch (type) {
            case COOPERATION_AGREEMENT -> """
                Extract and populate `cooperationAgreementData`:
                ```json
                {
                  "cooperationAgreementData": {
                    "cooperationScope": { "value": "string or null", "sourcePage": 1, "evidence": "...", "confidence": 0.95 },
                    "cooperationActivities": [
                      { "value": "string activity description", "sourcePage": 1, "evidence": "...", "confidence": 0.95 }
                    ],
                    "responsibilities": [
                      { "party": "Party Legal Name", "responsibility": "Description of responsibility", "sourcePage": 1, "evidence": "...", "confidence": 0.95 }
                    ],
                    "resourceCommitments": [
                      { "party": "Party Legal Name", "resourceType": "PERSONNEL | FINANCIAL | FACILITY | TECHNOLOGY | OTHER", "description": "...", "sourcePage": 1, "evidence": "...", "confidence": 0.95 }
                    ],
                    "informationSharing": { "value": "string or null", "sourcePage": 1, "evidence": "...", "confidence": 0.95 },
                    "coordinationMechanism": { "value": "string or null", "sourcePage": 1, "evidence": "...", "confidence": 0.95 },
                    "terminationConditions": [
                      { "value": "condition text", "sourcePage": 1, "evidence": "...", "confidence": 0.95 }
                    ]
                  }
                }
                ```
                """;
            case PARTNERSHIP_AGREEMENT -> """
                Extract and populate `partnershipAgreementData` containing EXACTLY these 9 categories:
                ```json
                {
                  "partnershipAgreementData": {
                    "partnershipScope": { "value": "Detailed scope of strategic partnership or null", "sourcePage": 1, "evidence": "...", "confidence": 0.95 },
                    "partnerRoles": [
                      { "party": "Party Legal Name", "role": "Substantive operational/business role (what the party operates, manages, coordinates, or executes)", "sourcePage": 1, "evidence": "...", "confidence": 0.95 }
                    ],
                    "mutualCommitments": [
                      { "party": "Party Legal Name or group reference (e.g. 'FIP Partners', 'The Parties')", "commitment": "Detailed commitment description", "sourcePage": 1, "evidence": "...", "confidence": 0.95 }
                    ],
                    "benefitSharing": { "value": "Benefit / commission / revenue sharing terms or null", "sourcePage": 1, "evidence": "...", "confidence": 0.95 },
                    "salesOrMarketRights": { "value": "Territory, distribution, or marketing rights or null", "sourcePage": 1, "evidence": "...", "confidence": 0.95 },
                    "exclusivity": {
                      "isExclusive": null,
                      "scope": null,
                      "sourcePage": 1,
                      "evidence": "...",
                      "confidence": 0.95
                    },
                    "performanceRequirements": [
                      { "requirement": "Measurable KPI / minimum performance obligation", "target": "Target metric / quantitative threshold or null", "sourcePage": 1, "evidence": "...", "confidence": 0.95 }
                    ],
                    "relationshipGovernance": { "value": "Coordination body, steering committee, periodic meetings, reporting, escalation mechanism, or review process or null", "sourcePage": 1, "evidence": "...", "confidence": 0.95 },
                    "terminationConditions": [
                      { "value": "Condition or notice period under which the partnership/MOU may terminate", "sourcePage": 1, "evidence": "...", "confidence": 0.95 }
                    ]
                  }
                }
                ```
                Strict Partnership Extraction & Anti-Hallucination Rules:
                1. Missing data: If a category is NOT supported by explicit text in the contract, return `null` (or `"value": null`), `exclusivity: { "isExclusive": null, "scope": null }`, or `[]` for arrays. DO NOT invent or infer terms.
                2. Exclusivity: If the document says nothing about exclusivity, return `"isExclusive": null, "scope": null`. ONLY return `"isExclusive": false` if the contract explicitly declares non-exclusivity. ONLY return `"isExclusive": true` if the contract explicitly grants exclusivity.
                3. Partner Roles vs Party Role: `partnerRoles` captures detailed business/project responsibilities (e.g., "Operates, manages, and coordinates the FIP Vung Tau project"), NOT generic party classifications ("PARTNER").
                4. Collective Parties: If commitments or roles apply to groups like "FIP Partners" or "The Parties", preserve that exact collective reference.
                5. Avoid Semantic Duplication: Do not duplicate ordinary mutual commitments into `performanceRequirements` unless the text explicitly defines measurable KPIs, quantitative targets, or minimum performance obligations.
                6. Termination Conditions: Extract the rules for termination. Extracting termination conditions does NOT mean the contract is terminated.
                """;
            case JOINT_VENTURE_AGREEMENT -> """
                Extract and populate `jointVentureAgreementData`:
                ```json
                {
                  "jointVentureAgreementData": {
                    "jointVentureName": { "value": "Name of joint venture company to be incorporated", "sourcePage": 1, "evidence": "...", "confidence": 0.95 },
                    "jointVenturePurpose": { "value": "Purpose of joint venture", "sourcePage": 1, "evidence": "...", "confidence": 0.95 },
                    "capitalContributions": [
                      { "party": "Party Legal Name", "amount": "string numeric amount, e.g. 20000000000", "currency": "VND", "contributionType": "CASH | ASSET | TECHNOLOGY | INTELLECTUAL_PROPERTY | OTHER", "sourcePage": 1, "evidence": "...", "confidence": 0.95 }
                    ],
                    "ownershipPercentages": [
                      { "party": "Party Legal Name", "percentage": "string percentage, e.g. 50.0", "sourcePage": 1, "evidence": "...", "confidence": 0.95 }
                    ],
                    "governanceStructure": { "value": "Board of Management / General Director structure", "sourcePage": 1, "evidence": "...", "confidence": 0.95 },
                    "votingRights": [
                      { "party": "Party Legal Name", "votingPercentage": "string percentage, e.g. 50.0", "description": "Voting rules / veto powers", "sourcePage": 1, "evidence": "...", "confidence": 0.95 }
                    ],
                    "decisionMakingRules": [
                      { "value": "Simple majority / unanimous consent items", "sourcePage": 1, "evidence": "...", "confidence": 0.95 }
                    ],
                    "profitDistribution": [
                      { "party": "Party Legal Name", "percentage": "string percentage, e.g. 50.0", "description": "Profit share terms", "sourcePage": 1, "evidence": "...", "confidence": 0.95 }
                    ],
                    "lossSharing": [
                      { "party": "Party Legal Name", "percentage": "string percentage, e.g. 50.0", "description": "Loss share terms", "sourcePage": 1, "evidence": "...", "confidence": 0.95 }
                    ],
                    "managementAppointments": [
                      { "position": "Chairman / General Director / Chief Accountant", "appointedBy": "Party Legal Name", "sourcePage": 1, "evidence": "...", "confidence": 0.95 }
                    ],
                    "exitConditions": [
                      { "value": "Buyout / dissolution / IPO exit terms", "sourcePage": 1, "evidence": "...", "confidence": 0.95 }
                    ],
                    "transferRestrictions": [
                      { "value": "Right of First Refusal / Tag-along / Drag-along", "sourcePage": 1, "evidence": "...", "confidence": 0.95 }
                    ]
                  }
                }
                ```
                """;
            case BUSINESS_COOPERATION_CONTRACT -> """
                Extract and populate `businessCooperationContractData`:
                ```json
                {
                  "businessCooperationContractData": {
                    "businessScope": { "value": "Scope of BCC cooperation", "sourcePage": 1, "evidence": "...", "confidence": 0.95 },
                    "contributions": [
                      { "party": "Party Legal Name", "amount": "string numeric amount, e.g. 10000000000", "currency": "VND", "contributionType": "CASH | ASSET | TECHNOLOGY | HUMAN_RESOURCE | OTHER", "description": "Contribution details", "sourcePage": 1, "evidence": "...", "confidence": 0.95 }
                    ],
                    "contributionRatios": [
                      { "party": "Party Legal Name", "ratioPercentage": "string percentage, e.g. 40.0", "sourcePage": 1, "evidence": "...", "confidence": 0.95 }
                    ],
                    "revenueSharing": [
                      { "party": "Party Legal Name", "percentage": "string percentage, e.g. 30.0", "description": "Revenue share terms", "sourcePage": 1, "evidence": "...", "confidence": 0.95 }
                    ],
                    "profitSharing": [
                      { "party": "Party Legal Name", "percentage": "string percentage, e.g. 50.0", "description": "Profit share terms", "sourcePage": 1, "evidence": "...", "confidence": 0.95 }
                    ],
                    "costSharing": [
                      { "party": "Party Legal Name", "percentage": "string percentage, e.g. 50.0", "description": "Cost share terms", "sourcePage": 1, "evidence": "...", "confidence": 0.95 }
                    ],
                    "lossSharing": [
                      { "party": "Party Legal Name", "percentage": "string percentage, e.g. 50.0", "description": "Loss share terms", "sourcePage": 1, "evidence": "...", "confidence": 0.95 }
                    ],
                    "rightsAndObligations": [
                      {
                        "party": "Party Legal Name",
                        "rights": ["Right to inspect project books", "Right to receive revenue share"],
                        "obligations": ["Provide land use right", "Obtain construction permits"],
                        "sourcePage": 1,
                        "evidence": "...",
                        "confidence": 0.95
                      }
                    ],
                    "managementMechanism": { "value": "Joint Management Board / Coordination Board structure", "sourcePage": 1, "evidence": "...", "confidence": 0.95 },
                    "financialManagement": { "value": "Designated escrow account / accounting mechanism", "sourcePage": 1, "evidence": "...", "confidence": 0.95 },
                    "assetOwnership": { "value": "Ownership of assets formed during BCC", "sourcePage": 1, "evidence": "...", "confidence": 0.95 },
                    "terminationSettlement": { "value": "Liquidation and asset distribution upon termination", "sourcePage": 1, "evidence": "...", "confidence": 0.95 }
                  }
                }
                ```
                """;
            default -> "";
        };
    }

    private String loadPrompt(String resourcePath) {
        try {
            ClassPathResource resource = new ClassPathResource(resourcePath);
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to load prompt: {}", resourcePath, e);
            throw new BusinessValidationException("PROMPT_LOAD_ERROR", "Failed to load system prompt: " + resourcePath);
        }
    }

    private String getCleanApiKey() {
        if (geminiApiKey == null) return "";
        return geminiApiKey.trim().replaceAll("^[`'\"\\s]+|[`'\"\\s]+$", "");
    }

    private String callGemini(String fullPrompt) {
        String cleanKey = getCleanApiKey();
        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + geminiModel + ":generateContent?key=" + cleanKey;

        Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(
                                Map.of("text", fullPrompt)
                        ))
                ),
                "generationConfig", Map.of(
                        "responseMimeType", "application/json"
                )
        );

        RestClient restClient = restClientBuilder.build();

        for (int attempt = 1; attempt <= MAX_GEMINI_RETRIES; attempt++) {
            try {
                return restClient.post()
                        .uri(url)
                        .header("x-goog-api-key", cleanKey)
                        .body(requestBody)
                        .retrieve()
                        .body(String.class);
            } catch (RestClientResponseException e) {
                int statusCode = e.getStatusCode().value();
                String errorBody = e.getResponseBodyAsString();
                log.warn("Gemini API attempt {}/{} failed with status {}: {}", attempt, MAX_GEMINI_RETRIES, statusCode, errorBody);

                if (statusCode == 404) {
                    throw new BusinessValidationException("GEMINI_MODEL_UNAVAILABLE", "Gemini model is unavailable: " + geminiModel);
                }
                if (statusCode == 401 || statusCode == 403) {
                    throw new BusinessValidationException("GEMINI_UNAUTHORIZED", "AI service authorization failed. Please check API key.");
                }

                // Retry on transient errors: 503 (Service Unavailable / High Demand), 429 (Rate Limit), 500, 502, 504
                if (attempt < MAX_GEMINI_RETRIES && (statusCode == 503 || statusCode == 429 || statusCode >= 500)) {
                    long backoffMs = INITIAL_BACKOFF_MS * attempt;
                    log.info("Gemini temporary overload ({}), retrying attempt {} after {}ms...", statusCode, attempt + 1, backoffMs);
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }

                if (statusCode == 503) {
                    throw new BusinessValidationException("GEMINI_HIGH_DEMAND", "Mô hình AI hiện đang quá tải tạm thời (503 High Demand). Vui lòng thử lại sau giây lát.");
                }
                if (statusCode == 429) {
                    throw new BusinessValidationException("GEMINI_RATE_LIMITED", "AI service is currently busy. Please retry in a few moments.");
                }
                throw new BusinessValidationException("GEMINI_CALL_FAILED", "AI generation failed with status: " + statusCode);
            } catch (BusinessValidationException e) {
                throw e;
            } catch (Exception e) {
                log.error("Unexpected error invoking Gemini on attempt " + attempt, e);
                if (attempt == MAX_GEMINI_RETRIES) {
                    throw new BusinessValidationException("GEMINI_ERROR", "AI extraction failed. Please retry.");
                }
            }
        }
        throw new BusinessValidationException("GEMINI_CALL_FAILED", "AI extraction failed after multiple retries.");
    }

    private String callGeminiMultimodal(String prompt, String mimeType, String base64Data) {
        String cleanKey = getCleanApiKey();
        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + geminiModel + ":generateContent?key=" + cleanKey;

        Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(
                                Map.of("text", prompt),
                                Map.of("inlineData", Map.of(
                                        "mimeType", mimeType,
                                        "data", base64Data
                                ))
                        ))
                ),
                "generationConfig", Map.of(
                        "responseMimeType", "application/json"
                )
        );

        RestClient restClient = restClientBuilder.build();

        for (int attempt = 1; attempt <= MAX_GEMINI_RETRIES; attempt++) {
            try {
                return restClient.post()
                        .uri(url)
                        .header("x-goog-api-key", cleanKey)
                        .body(requestBody)
                        .retrieve()
                        .body(String.class);
            } catch (RestClientResponseException e) {
                int statusCode = e.getStatusCode().value();
                String errorBody = e.getResponseBodyAsString();
                log.warn("Gemini Multimodal attempt {}/{} failed with status {}: {}", attempt, MAX_GEMINI_RETRIES, statusCode, errorBody);

                if (statusCode == 404) {
                    throw new BusinessValidationException("GEMINI_MODEL_UNAVAILABLE", "Gemini model is unavailable: " + geminiModel);
                }
                if (statusCode == 401 || statusCode == 403) {
                    throw new BusinessValidationException("GEMINI_UNAUTHORIZED", "AI service unauthorized. Please check API key.");
                }
                if (statusCode == 400) {
                    log.warn("Gemini 400 Bad Request on Multimodal. Falling back to text extraction.");
                    return null;
                }

                // Retry on transient errors: 503 (Service Unavailable / High Demand), 429 (Rate Limit), 500, 502, 504
                if (attempt < MAX_GEMINI_RETRIES && (statusCode == 503 || statusCode == 429 || statusCode >= 500)) {
                    long backoffMs = INITIAL_BACKOFF_MS * attempt;
                    log.info("Gemini temporary overload ({}), retrying multimodal attempt {} after {}ms...", statusCode, attempt + 1, backoffMs);
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }

                if (statusCode == 503) {
                    throw new BusinessValidationException("GEMINI_HIGH_DEMAND", "Mô hình AI hiện đang quá tải tạm thời (503 High Demand). Vui lòng thử lại sau giây lát.");
                }
                if (statusCode == 429) {
                    throw new BusinessValidationException("GEMINI_RATE_LIMITED", "AI service is busy. Please retry in a few moments.");
                }
                throw new BusinessValidationException("GEMINI_CALL_FAILED", "AI generation failed with status: " + statusCode);
            } catch (BusinessValidationException e) {
                throw e;
            } catch (Exception e) {
                log.error("Unexpected error in Gemini Multimodal on attempt " + attempt, e);
                if (attempt == MAX_GEMINI_RETRIES) return null;
            }
        }
        return null;
    }

    private <T> T parseResponse(String responseJson, Class<T> clazz) {
        if (responseJson == null || responseJson.isBlank()) {
            throw new BusinessValidationException("AI_RESPONSE_EMPTY", "AI returned an empty response.");
        }

        try {
            Map<String, Object> root = objectMapper.readValue(responseJson, Map.class);
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) root.get("candidates");
            if (candidates == null || candidates.isEmpty()) {
                throw new BusinessValidationException("AI_NO_CANDIDATES", "AI returned no candidate responses.");
            }

            Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
            List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
            if (parts == null || parts.isEmpty()) {
                throw new BusinessValidationException("AI_EMPTY_PARTS", "AI response parts are empty.");
            }

            String text = (String) parts.get(0).get("text");
            if (text != null) {
                text = text.trim();
                if (text.startsWith("```json")) {
                    text = text.substring(7);
                } else if (text.startsWith("```")) {
                    text = text.substring(3);
                }
                if (text.endsWith("```")) {
                    text = text.substring(0, text.length() - 3);
                }
                text = text.trim();
            }
            return objectMapper.readValue(text, clazz);
        } catch (BusinessValidationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse Gemini response: {}", responseJson, e);
            throw new BusinessValidationException("AI_PARSE_ERROR", "Failed to parse AI structured response.");
        }
    }
}
