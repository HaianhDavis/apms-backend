package com.apms.domain.financial.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.document.RawDocument;
import com.apms.domain.financial.dto.FinancialDocumentExtractionResult;
import com.apms.domain.financial.dto.FinancialExtractionResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class FinancialExtractionService {

    private final ObjectMapper objectMapper;
    private final RestClient.Builder restClientBuilder;

    @Value("${app.ai.gemini.api-key:dummy-key}")
    private String geminiApiKey;

    @Value("${app.ai.gemini.model:gemini-3.6-flash}")
    private String geminiModel;

    @Value("${app.storage.upload-dir:uploads/}")
    private String storagePath;

    public FinancialExtractionResponse extractDocuments(List<RawDocument> documents) {
        List<FinancialDocumentExtractionResult> results = new ArrayList<>();

        for (RawDocument doc : documents) {
            extractDocument(doc).ifPresent(results::add);
        }

        return FinancialExtractionResponse.builder()
                .documents(results)
                .build();
    }

    public Optional<FinancialDocumentExtractionResult> extractDocument(RawDocument doc) {
        try {
            if (!isExtractable(doc)) {
                log.warn("Document {} contains no extractable data", doc.getId());
                return Optional.empty();
            }

            return Optional.ofNullable(callAiExtraction(doc));
        } catch (BusinessValidationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to extract data from document {}", doc.getId(), e);
            return Optional.empty();
        }
    }

    /**
     * Parse PDF/text from a RawDocument. This is the PARSING_DOCUMENT stage.
     * Made public so the async worker can call it as a separate stage.
     */
    /**
     * Check if a document can be extracted (has cached text or is a PDF file).
     */
    public boolean isExtractable(RawDocument doc) {
        String docPath = doc.getStorage() != null ? doc.getStorage().getPath() : null;
        if (docPath != null) {
            File file = new File(storagePath, docPath);
            if (file.exists() && file.getName().toLowerCase().endsWith(".pdf")) {
                return true; // We can use Multimodal API
            }
        }
        
        // Otherwise, see if we have cached text or if we can extract via text parser
        try {
            String text = parseDocumentText(doc);
            return !text.isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    public String parseDocumentText(RawDocument doc) {
        String cachedText = doc.getSource() != null ? doc.getSource().getInputText() : null;
        String sourceType = doc.getSource() != null ? doc.getSource().getType() : null;
        if (cachedText != null && !cachedText.isBlank() && !"PDF".equalsIgnoreCase(sourceType)) {
            return "=== PAGE 1 ===\n" + cachedText;
        }

        String docPath = doc.getStorage() != null ? doc.getStorage().getPath() : null;
        if (docPath == null) {
            if (cachedText != null && !cachedText.isBlank()) {
                return "=== PAGE 1 ===\n" + cachedText;
            }
            throw new BusinessValidationException("Cannot extract: document has no storage path and no cached text.");
        }
        File file = new File(storagePath, docPath);
        if (!file.exists()) {
            if (cachedText != null && !cachedText.isBlank()) {
                log.warn("Storage file is missing for document {}; using cached extracted text", doc.getId());
                return "=== PAGE 1 ===\n" + cachedText;
            }
            throw new BusinessValidationException("Cannot extract: PDF file not found on server (" + file.getName() + ").");
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
            throw new BusinessValidationException("Failed to read PDF file: " + e.getMessage());
        }
        return sb.toString();
    }

    public FinancialDocumentExtractionResult callAiExtraction(RawDocument doc) {
        String promptTemplate = loadPrompt();
        
        String docPath = doc.getStorage() != null ? doc.getStorage().getPath() : null;
        if (docPath != null) {
            File file = new File(storagePath, docPath);
            if (file.exists() && file.getName().toLowerCase().endsWith(".pdf")) {
                try {
                    byte[] fileBytes = java.nio.file.Files.readAllBytes(file.toPath());
                    String base64 = java.util.Base64.getEncoder().encodeToString(fileBytes);
                    log.info("Sending PDF directly to Gemini via Multimodal API: {}", file.getName());
                    return callGeminiMultimodal(promptTemplate, "application/pdf", base64);
                } catch (IOException e) {
                    log.warn("Failed to read PDF for multimodal extraction, falling back to text", e);
                }
            }
        }
        
        String text = parseDocumentText(doc);
        if (text.isBlank()) {
            return null;
        }
        return callGemini(promptTemplate, text);
    }

    private String loadPrompt() {
        try {
            ClassPathResource resource = new ClassPathResource("ai-prompts/financial-extraction.prompt.md");
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to load financial extraction prompt", e);
            return "Extract financial data from the following document text as JSON.";
        }
    }

    private String getCleanApiKey() {
        if (geminiApiKey == null) return "";
        return geminiApiKey.trim().replaceAll("^[`'\"\\s]+|[`'\"\\s]+$", "");
    }

    private FinancialDocumentExtractionResult callGemini(String promptTemplate, String text) {
        try {
            String cleanKey = getCleanApiKey();
            String url = "https://generativelanguage.googleapis.com/v1beta/models/" + geminiModel + ":generateContent?key=" + cleanKey;

            String combinedPrompt = promptTemplate + "\n\n=== DOCUMENT TEXT ===\n" + text;

            Map<String, Object> requestBody = Map.of(
                    "contents", List.of(
                            Map.of("parts", List.of(
                                    Map.of("text", combinedPrompt)
                            ))
                    ),
                    "generationConfig", Map.of(
                            "responseMimeType", "application/json",
                            "temperature", 0.0
                    )
            );

            RestClient restClient = restClientBuilder.build();
            String response = restClient.post()
                    .uri(url)
                    .header("x-goog-api-key", cleanKey)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            return parseGeminiResponse(response);
        } catch (RestClientResponseException e) {
            log.error("Failed to call Gemini API: {}", e.getResponseBodyAsString(), e);
            int statusCode = e.getStatusCode().value();
            if (statusCode == 404) {
                throw new BusinessValidationException("Gemini model is unavailable. Set GEMINI_MODEL to a supported model, for example gemini-3.6-flash.");
            }
            if (statusCode == 429) {
                throw new BusinessValidationException("Gemini API rate limit exceeded. Please retry later.");
            }
            if (statusCode == 401 || statusCode == 403) {
                throw new BusinessValidationException("Gemini API key is invalid or unauthorized.");
            }
            throw new BusinessValidationException("Gemini API call failed: " + e.getStatusCode());
        } catch (BusinessValidationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to call Gemini API", e);
            throw new BusinessValidationException("Gemini extraction failed. Please retry later.");
        }
    }

    private FinancialDocumentExtractionResult callGeminiMultimodal(String promptTemplate, String mimeType, String base64Data) {
        try {
            String cleanKey = getCleanApiKey();
            String url = "https://generativelanguage.googleapis.com/v1beta/models/" + geminiModel + ":generateContent?key=" + cleanKey;

            Map<String, Object> requestBody = Map.of(
                    "contents", List.of(
                            Map.of("parts", List.of(
                                    Map.of("text", promptTemplate),
                                    Map.of("inlineData", Map.of(
                                            "mimeType", mimeType,
                                            "data", base64Data
                                    ))
                            ))
                    ),
                    "generationConfig", Map.of(
                            "responseMimeType", "application/json",
                            "temperature", 0.0
                    )
            );

            RestClient restClient = restClientBuilder.build();
            String response = restClient.post()
                    .uri(url)
                    .header("x-goog-api-key", cleanKey)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            return parseGeminiResponse(response);
        } catch (RestClientResponseException e) {
            log.error("Failed to call Gemini Multimodal API: {}", e.getResponseBodyAsString(), e);
            int statusCode = e.getStatusCode().value();
            if (statusCode == 404) {
                throw new BusinessValidationException("Gemini model is unavailable. Set GEMINI_MODEL to a supported model, for example gemini-1.5-flash.");
            }
            if (statusCode == 429) {
                throw new BusinessValidationException("Gemini API rate limit exceeded. Please retry later.");
            }
            if (statusCode == 401 || statusCode == 403) {
                throw new BusinessValidationException("Gemini API key is invalid or unauthorized.");
            }
            if (statusCode == 400) {
                log.error("Bad Request from Gemini. It might be due to file size limit (base64 inlineData limit). Falling back to text extraction if possible.");
                return null;
            }
            throw new BusinessValidationException("Gemini API call failed: " + e.getStatusCode());
        } catch (BusinessValidationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to call Gemini Multimodal API", e);
            throw new BusinessValidationException("Gemini extraction failed. Please retry later.");
        }
    }

    private FinancialDocumentExtractionResult parseGeminiResponse(String responseJson) {
        log.info("Gemini raw response: {}", responseJson);
        try {
            Map<String, Object> root = objectMapper.readValue(responseJson, Map.class);
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) root.get("candidates");
            if (candidates == null || candidates.isEmpty()) {
                return null;
            }

            Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
            List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
            if (parts == null || parts.isEmpty()) {
                return null;
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
            return objectMapper.readValue(text, FinancialDocumentExtractionResult.class);
        } catch (Exception e) {
            log.error("Failed to parse Gemini response", e);
            return null;
        }
    }
}
