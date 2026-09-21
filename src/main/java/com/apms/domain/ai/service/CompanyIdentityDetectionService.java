package com.apms.domain.ai.service;

import com.apms.domain.ai.dto.DocumentCompanyIdentity;
import com.apms.domain.ai.service.provider.GeminiExtractionProvider;
import com.apms.domain.ai.service.provider.GeminiRequestExecutor;
import com.apms.domain.document.RawDocument;
import com.apms.domain.document.repository.mongo.RawDocumentRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyIdentityDetectionService {

    private static final String GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1/models/{model}:generateContent?key={key}";

    private final GeminiExtractionProvider geminiProvider; // for lightweight AI fallback
    private final GeminiRequestExecutor geminiRequestExecutor;
    private final CompanyNameNormalizer companyNameNormalizer;
    private final RawDocumentRepository rawDocumentRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.storage.upload-dir:uploads/}")
    private String uploadDir;

    @Value("${app.ai.gemini.model:gemini-3.6-flash}")
    private String geminiModel;

    // Vietnamese tax code pattern: 10 digits, optionally followed by dash and 3 more digits
    private static final Pattern TAX_CODE_PATTERN = Pattern.compile("\\b(\\d{10}(?:-\\d{3})?)\\b");

    // Vietnamese company name patterns
    private static final Pattern VN_COMPANY_PATTERN = Pattern.compile(
            "(?i)(?:Công ty (?:Cổ phần|TNHH|Trách nhiệm hữu hạn)(?:\\s+[A-ZÀ-Ỹa-zà-ỹ0-9]+)+)"
    );

    // English company name patterns (e.g., Western Holdings, LG Electronics Corp)
    private static final Pattern EN_COMPANY_PATTERN = Pattern.compile(
            "(?i)\\b([A-Z][a-zA-Z0-9&\\-\\s]+(?:\\s+(?:Corporation|Corp\\.?|Inc\\.?|Incorporated|LLC|Ltd\\.?|Limited|Company|Holdings|Group)))\\b"
    );

    // English company name pattern after label
    private static final Pattern LABELED_NAME_PATTERN = Pattern.compile(
            "(?i)(?:Company\\s*Name|Legal\\s*Name|Tên\\s*(?:công ty|doanh nghiệp))\\s*[:：]\\s*(.+?)(?:\\n|$)"
    );

    // Registration number pattern
    private static final Pattern REG_NUMBER_PATTERN = Pattern.compile(
            "(?i)(?:Số\\s*(?:ĐKKD|đăng ký|giấy phép)|Registration\\s*(?:No|Number))\\s*[:：]?\\s*(\\d[\\d\\-/]+\\d)"
    );

    // Website pattern
    private static final Pattern WEBSITE_PATTERN = Pattern.compile(
            "(?i)(?:https?://)?(?:www\\.)?([a-zA-Z0-9](?:[a-zA-Z0-9\\-]*[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}"
    );

    /**
     * Detect the company identity from a single document.
     * Strategy: 1) Check cache, 2) Try deterministic regex, 3) Fallback to lightweight AI.
     */
    public DocumentCompanyIdentity detectIdentity(RawDocument document) {
        String fileName = document.getSource() != null ? document.getSource().getFileName() : "Unknown";

        // 1. Check cache in document's processing metadata
        // We store identity detection in a custom way - checking if source.companyNameHint has cached data
        // But actually, let's use a simple in-memory approach for now and rely on the document text

        // 2. Get document text
        String text = getDocumentText(document);
        if (!StringUtils.hasText(text)) {
            log.warn("No text available for document {} ({})", document.getId(), fileName);
            return DocumentCompanyIdentity.builder()
                    .rawDocumentId(document.getId())
                    .fileName(fileName)
                    .status("UNKNOWN")
                    .confidence(0.0)
                    .build();
        }

        // 3. Try deterministic extraction
        DocumentCompanyIdentity identity = tryDeterministicExtraction(document.getId(), fileName, text);
        if (identity != null && "RESOLVED".equals(identity.getStatus()) && identity.getConfidence() >= 0.6) {
            log.info("Deterministic identity detected for doc {}: {} (confidence={})",
                    document.getId(), identity.getLegalName(), identity.getConfidence());
            return identity;
        }

        // 4. Fallback to lightweight AI detection
        log.info("Falling back to AI identity detection for doc {} ({})", document.getId(), fileName);
        return aiDetectIdentity(document.getId(), fileName, text);
    }

    private DocumentCompanyIdentity tryDeterministicExtraction(String docId, String fileName, String text) {
        String legalName = null;
        String tradeName = null;
        String taxCode = null;
        String registrationNumber = null;
        String websiteDomain = null;

        // Extract tax code
        Matcher taxMatcher = TAX_CODE_PATTERN.matcher(text);
        if (taxMatcher.find()) {
            String candidate = taxMatcher.group(1);
            // Validate: Vietnamese tax codes are 10 or 13 digits (10-XXX)
            String digits = candidate.replace("-", "");
            if (digits.length() == 10 || digits.length() == 13) {
                taxCode = candidate;
            }
        }

        // Extract Vietnamese company name
        Matcher vnMatcher = VN_COMPANY_PATTERN.matcher(text);
        if (vnMatcher.find()) {
            legalName = vnMatcher.group(0).trim();
        } else {
            // Extract English company name
            Matcher enMatcher = EN_COMPANY_PATTERN.matcher(text);
            if (enMatcher.find()) {
                legalName = enMatcher.group(1).trim();
            }
        }

        // Extract labeled company name
        Matcher labeledMatcher = LABELED_NAME_PATTERN.matcher(text);
        if (labeledMatcher.find()) {
            String found = labeledMatcher.group(1).trim();
            if (legalName == null) {
                legalName = found;
            } else if (!companyNameNormalizer.isSameCompany(legalName, found)) {
                tradeName = found; // Different name - could be trade name
            }
        }

        // Extract registration number
        Matcher regMatcher = REG_NUMBER_PATTERN.matcher(text);
        if (regMatcher.find()) {
            registrationNumber = regMatcher.group(1).trim();
        }

        // Extract website
        Matcher webMatcher = WEBSITE_PATTERN.matcher(text);
        if (webMatcher.find()) {
            String url = webMatcher.group(0);
            // Filter out common non-company websites
            String domain = companyNameNormalizer.normalizeDomain(url);
            if (!isCommonDomain(domain)) {
                websiteDomain = domain;
            }
        }

        // Determine confidence
        double confidence = 0.0;
        if (StringUtils.hasText(taxCode)) confidence += 0.4;
        if (StringUtils.hasText(legalName)) confidence += 0.3;
        if (StringUtils.hasText(registrationNumber)) confidence += 0.2;
        if (StringUtils.hasText(websiteDomain)) confidence += 0.1;

        if (confidence < 0.3) {
            return null; // Not enough deterministic signals
        }

        return DocumentCompanyIdentity.builder()
                .rawDocumentId(docId)
                .fileName(fileName)
                .legalName(legalName)
                .tradeName(tradeName)
                .taxCode(taxCode)
                .registrationNumber(registrationNumber)
                .websiteDomain(websiteDomain)
                .status("RESOLVED")
                .confidence(confidence)
                .build();
    }

    private DocumentCompanyIdentity aiDetectIdentity(String docId, String fileName, String text) {
        try {
            // Use only first ~3000 chars for lightweight detection
            String excerpt = text.length() > 3000 ? text.substring(0, 3000) : text;

            String prompt = """
                    Analyze the following document excerpt and identify the PRIMARY company/organization this document belongs to or is about.
                    
                    Extract ONLY these fields (return as JSON):
                    {
                      "legalName": "Official legal name of the primary company",
                      "tradeName": "Trade/brand name if different from legalName",
                      "taxCode": "Tax identification number if found",
                      "registrationNumber": "Business registration number if found",
                      "website": "Company website URL if found",
                      "confidence": "HIGH or MEDIUM or LOW",
                      "status": "RESOLVED or AMBIGUOUS or UNKNOWN"
                    }
                    
                    IMPORTANT RULES:
                    - Identify ONLY the PRIMARY subject company, not every company mentioned.
                    - If the document is about multiple companies (contract, comparison, news), identify the PRIMARY subject. If unclear, set status to AMBIGUOUS.
                    - If you cannot determine any company, set status to UNKNOWN.
                    - Do NOT guess. Only extract what is clearly present.
                    
                    Document excerpt:
                    """ + excerpt;

            // Use a lightweight Gemini call - we construct a minimal request
            org.springframework.web.client.RestClient restClient = org.springframework.web.client.RestClient.create();

            Map<String, Object> requestBody = Map.of(
                    "contents", List.of(
                            Map.of("parts", List.of(Map.of("text", prompt)))
                    ),
                    "generationConfig", Map.of(
                            "responseMimeType", "application/json",
                            "temperature", 0.1
                    )
            );

            String responseBody = geminiRequestExecutor.execute(apiKey -> restClient.post()
                    .uri(GEMINI_API_URL, geminiModel, apiKey)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class));

            // Parse Gemini response
            JsonNode root = objectMapper.readTree(responseBody);
            String responseText = root.path("candidates").get(0)
                    .path("content").path("parts").get(0)
                    .path("text").asText();

            // Clean markdown fences if present
            responseText = responseText.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();

            JsonNode identityJson = objectMapper.readTree(responseText);

            String status = identityJson.has("status") ? identityJson.get("status").asText("UNKNOWN") : "UNKNOWN";
            String confidenceStr = identityJson.has("confidence") ? identityJson.get("confidence").asText("LOW") : "LOW";
            double confidence = switch (confidenceStr.toUpperCase()) {
                case "HIGH" -> 0.9;
                case "MEDIUM" -> 0.7;
                default -> 0.4;
            };

            String website = identityJson.has("website") ? identityJson.get("website").asText(null) : null;
            String websiteDomain = StringUtils.hasText(website) ? companyNameNormalizer.normalizeDomain(website) : null;

            return DocumentCompanyIdentity.builder()
                    .rawDocumentId(docId)
                    .fileName(fileName)
                    .legalName(identityJson.has("legalName") ? identityJson.get("legalName").asText(null) : null)
                    .tradeName(identityJson.has("tradeName") ? identityJson.get("tradeName").asText(null) : null)
                    .taxCode(identityJson.has("taxCode") ? identityJson.get("taxCode").asText(null) : null)
                    .registrationNumber(identityJson.has("registrationNumber") ? identityJson.get("registrationNumber").asText(null) : null)
                    .websiteDomain(websiteDomain)
                    .status(status)
                    .confidence(confidence)
                    .build();

        } catch (Exception e) {
            log.warn("AI identity detection failed for doc {}. Returning UNKNOWN identity.", docId);
            return DocumentCompanyIdentity.builder()
                    .rawDocumentId(docId)
                    .fileName(fileName)
                    .status("UNKNOWN")
                    .confidence(0.0)
                    .build();
        }
    }

    private String getDocumentText(RawDocument document) {
        // Check manual input first
        if (document.getSource() != null && "MANUAL_INPUT".equals(document.getSource().getType())) {
            return document.getSource().getInputText();
        }

        // Extract text from file
        if (document.getStorage() == null || !StringUtils.hasText(document.getStorage().getPath())) {
            return "";
        }

        String mimeType = document.getStorage().getMimeType();
        if ("application/pdf".equals(mimeType)) {
            try {
                File file = Paths.get(uploadDir, document.getStorage().getPath()).toFile();
                if (!file.exists()) return "";
                try (PDDocument pdfDoc = Loader.loadPDF(file)) {
                    PDFTextStripper stripper = new PDFTextStripper();
                    return stripper.getText(pdfDoc);
                }
            } catch (Exception e) {
                log.error("Failed to extract text from PDF {}: {}", document.getId(), e.getMessage());
                return "";
            }
        }

        // Try reading as plain text
        try {
            return java.nio.file.Files.readString(Paths.get(uploadDir, document.getStorage().getPath()));
        } catch (Exception e) {
            return "";
        }
    }

    private boolean isCommonDomain(String domain) {
        if (!StringUtils.hasText(domain)) return true;
        List<String> common = List.of("google.com", "facebook.com", "youtube.com", "linkedin.com",
                "twitter.com", "x.com", "wikipedia.org", "github.com", "gmail.com");
        return common.stream().anyMatch(domain::endsWith);
    }
}
