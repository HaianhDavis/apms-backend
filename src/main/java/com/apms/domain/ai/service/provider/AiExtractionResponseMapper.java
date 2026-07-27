package com.apms.domain.ai.service.provider;

import com.apms.domain.ai.dto.ExtractedCompanyData;
import com.apms.domain.ai.dto.ExtractionFieldResult;
import com.apms.domain.ai.dto.RawExtractionOutput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class AiExtractionResponseMapper {

    private final ObjectMapper objectMapper;

    public AiExtractionResponseMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public RawExtractionOutput mapResponse(String rawAiOutput) throws Exception {
        Map<String, ExtractionFieldResult> fieldResults = new HashMap<>();

        try {
            JsonNode root = objectMapper.readTree(rawAiOutput);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("AI output must be a JSON object");
            }

            Map<String, Object> flatMap = new HashMap<>();
            root.fields().forEachRemaining(entry -> {
                String originalFieldName = entry.getKey();
                String fieldName = canonicalFieldName(originalFieldName);
                if (!ALLOWED_FIELDS.contains(fieldName)) {
                    log.debug("Skipping unsupported AI extraction field: {}", originalFieldName);
                    return;
                }
                JsonNode fieldNode = entry.getValue();
                JsonNode valueNode = valueNode(fieldNode);
                Object value = objectMapper.convertValue(valueNode, Object.class);
                Object normalizedValue = normalizeValue(fieldName, value);

                flatMap.put(fieldName, normalizedValue);
                fieldResults.put(fieldName, ExtractionFieldResult.builder()
                        .fieldName(fieldName)
                        .value(normalizedValue)
                        .confidence(doubleValue(fieldNode, "confidence"))
                        .evidenceText(textValue(fieldNode, "evidenceText"))
                        .pageNumber(integerValue(fieldNode, "pageNumber"))
                        .build());
            });

            ExtractedCompanyData extractedData = objectMapper.convertValue(flatMap, ExtractedCompanyData.class);

            return RawExtractionOutput.builder()
                    .extractedData(extractedData)
                    .fieldResults(fieldResults)
                    .rawAiOutputString(rawAiOutput)
                    .build();
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse AI extraction JSON. Error: {}. Nearby output: {}",
                    e.getOriginalMessage(), outputExcerptNear(rawAiOutput, e.getLocation() != null ? e.getLocation().getCharOffset() : -1));
            throw e;
        } catch (Exception e) {
            log.warn("Failed to map AI extraction output. Error: {}", e.getMessage());
            throw e;
        }
    }

    private String outputExcerptNear(String rawAiOutput, long charOffset) {
        if (rawAiOutput == null || rawAiOutput.isBlank()) {
            return "";
        }
        if (charOffset < 0 || charOffset >= rawAiOutput.length()) {
            return rawAiOutput.length() <= 1000 ? rawAiOutput : rawAiOutput.substring(0, 1000) + "...";
        }
        int start = Math.max(0, (int) charOffset - 350);
        int end = Math.min(rawAiOutput.length(), (int) charOffset + 350);
        return rawAiOutput.substring(start, end).replaceAll("\\s+", " ").trim();
    }

    private String canonicalFieldName(String fieldName) {
        if ("productsServices".equals(fieldName)) {
            return "products";
        }
        if ("services".equals(fieldName)) {
            return "products";
        }
        if ("targetMarkets".equals(fieldName)) {
            return "markets";
        }
        if ("targetMarket".equals(fieldName)) {
            return "markets";
        }
        if ("targetCustomer".equals(fieldName)
                || "customerSegments".equals(fieldName)
                || "targetCustomerSegments".equals(fieldName)) {
            return "targetCustomers";
        }
        if ("weakness".equals(fieldName)
                || "weakneses".equals(fieldName)
                || "weekness".equals(fieldName)
                || "weeknesses".equals(fieldName)) {
            return "weaknesses";
        }
        return fieldName;
    }

    private JsonNode valueNode(JsonNode fieldNode) {
        if (fieldNode != null && fieldNode.isObject() && fieldNode.has("value")) {
            return fieldNode.get("value");
        }
        return fieldNode;
    }

    private Object normalizeValue(String fieldName, Object value) {
        if (value == null) {
            return null;
        }

        if (STRING_FIELDS.contains(fieldName)) {
            return normalizeString(value);
        }

        if (STRING_LIST_FIELDS.contains(fieldName)) {
            return normalizeStringList(value);
        }

        if ("products".equals(fieldName)) {
            return normalizeProducts(value);
        }

        if ("financial".equals(fieldName)) {
            return normalizeFinancial(value);
        }

        if ("market".equals(fieldName)) {
            return normalizeMarket(value);
        }

        if ("innovation".equals(fieldName)) {
            return normalizeInnovation(value);
        }

        if ("risk".equals(fieldName)) {
            return normalizeStringObject(value, RISK_STRING_FIELDS, Set.of());
        }

        if ("compliance".equals(fieldName)) {
            return normalizeStringObject(value, COMPLIANCE_STRING_FIELDS, COMPLIANCE_STRING_LIST_FIELDS);
        }

        return value;
    }

    private String normalizeString(Object value) {
        value = unwrapAiValue(value);
        if (value == null) {
            return null;
        }
        if (value instanceof String str) {
            return str;
        }
        if (value instanceof Map<?, ?> map) {
            for (String key : List.of("value", "name", "text", "label", "fullAddress")) {
                Object nested = map.get(key);
                if (nested != null) {
                    return normalizeString(nested);
                }
            }
            try {
                return objectMapper.writeValueAsString(value);
            } catch (Exception ignored) {
                return String.valueOf(value);
            }
        }
        return String.valueOf(value);
    }

    private List<String> normalizeStringList(Object value) {
        value = unwrapAiValue(value);
        if (value == null) {
            return null;
        }
        List<String> values = new ArrayList<>();
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                String normalized = normalizeString(item);
                if (normalized != null && !normalized.isBlank()) {
                    values.add(normalized);
                }
            }
            return values;
        }

        String normalized = normalizeString(value);
        if (normalized != null && !normalized.isBlank()) {
            values.add(normalized);
        }
        return values;
    }

    private List<Map<String, Object>> normalizeProducts(Object value) {
        value = unwrapAiValue(value);
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }

        List<Map<String, Object>> products = new ArrayList<>();
        for (Object item : iterable) {
            Object unwrapped = unwrapAiValue(item);
            if (!(unwrapped instanceof Map<?, ?>)) {
                String name = normalizeString(unwrapped);
                if (name != null && !name.isBlank()) {
                    Map<String, Object> product = new LinkedHashMap<>();
                    product.put("name", name);
                    product.put("category", null);
                    product.put("description", null);
                    products.add(product);
                }
                continue;
            }
            if (!(unwrapped instanceof Map<?, ?> itemMap)) {
                continue;
            }

            Map<String, Object> product = new LinkedHashMap<>();
            product.put("name", normalizeString(itemMap.get("name")));
            product.put("category", normalizeString(itemMap.get("category")));
            product.put("description", normalizeString(itemMap.get("description")));
            products.add(product);
        }
        return products;
    }

    private Map<String, Object> normalizeFinancial(Object value) {
        Map<String, Object> map = objectMap(value);
        if (map == null) {
            return null;
        }

        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("revenue", normalizeBigDecimal(map.get("revenue")));
        normalized.put("revenueCurrency", normalizeString(map.get("revenueCurrency")));
        normalized.put("revenueGrowth", normalizeBigDecimal(map.get("revenueGrowth")));
        normalized.put("debtRatio", normalizeBigDecimal(map.get("debtRatio")));
        normalized.put("profitMargin", normalizeBigDecimal(map.get("profitMargin")));
        normalized.put("fundingStage", normalizeString(map.get("fundingStage")));
        normalized.put("profitability", normalizeString(map.get("profitability")));
        return normalized;
    }

    private Map<String, Object> normalizeMarket(Object value) {
        Map<String, Object> map = objectMap(value);
        if (map == null) {
            return null;
        }

        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("marketShare", normalizeBigDecimal(map.get("marketShare")));
        normalized.put("brandRank", normalizeInteger(map.get("brandRank")));
        normalized.put("clientCount", normalizeLong(map.get("clientCount")));
        normalized.put("mainMarkets", normalizeStringList(map.get("mainMarkets")));
        return normalized;
    }

    private Map<String, Object> normalizeInnovation(Object value) {
        Map<String, Object> map = objectMap(value);
        if (map == null) {
            return null;
        }

        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("patents", normalizeInteger(map.get("patents")));
        normalized.put("rdInvestmentPercent", normalizeBigDecimal(map.get("rdInvestmentPercent")));
        normalized.put("techStack", normalizeStringList(map.get("techStack")));
        normalized.put("techMaturityLevel", normalizeInteger(map.get("techMaturityLevel")));
        normalized.put("productInnovationRate", normalizeBigDecimal(map.get("productInnovationRate")));
        normalized.put("technologyCapabilities", normalizeStringList(map.get("technologyCapabilities")));
        return normalized;
    }

    private Map<String, Object> normalizeStringObject(Object value, Set<String> stringFields, Set<String> stringListFields) {
        Map<String, Object> map = objectMap(value);
        if (map == null) {
            return null;
        }

        Map<String, Object> normalized = new LinkedHashMap<>();
        for (String field : stringFields) {
            normalized.put(field, normalizeString(map.get(field)));
        }
        for (String field : stringListFields) {
            normalized.put(field, normalizeStringList(map.get(field)));
        }
        return normalized;
    }

    private Map<String, Object> objectMap(Object value) {
        value = unwrapAiValue(value);
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }

        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                normalized.put(String.valueOf(entry.getKey()), unwrapAiValue(entry.getValue()));
            }
        }
        return normalized;
    }

    private Object unwrapAiValue(Object value) {
        if (value instanceof Map<?, ?> map && map.containsKey("value") && looksLikeAiFieldResponse(map)) {
            return unwrapAiValue(map.get("value"));
        }
        return value;
    }

    private boolean looksLikeAiFieldResponse(Map<?, ?> map) {
        return map.containsKey("confidence")
                || map.containsKey("evidenceText")
                || map.containsKey("pageNumber")
                || map.size() == 1;
    }

    private BigDecimal normalizeBigDecimal(Object value) {
        value = unwrapAiValue(value);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        String normalized = normalizeString(value);
        if (normalized == null || normalized.isBlank()) {
            return null;
        }
        String numeric = normalized.replaceAll("[^0-9+\\-.]", "");
        if (numeric.isBlank() || "+".equals(numeric) || "-".equals(numeric) || ".".equals(numeric)) {
            return null;
        }
        try {
            return new BigDecimal(numeric);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Integer normalizeInteger(Object value) {
        BigDecimal decimal = normalizeBigDecimal(value);
        return decimal != null ? decimal.intValue() : null;
    }

    private Long normalizeLong(Object value) {
        BigDecimal decimal = normalizeBigDecimal(value);
        return decimal != null ? decimal.longValue() : null;
    }

    private String textValue(JsonNode fieldNode, String key) {
        if (fieldNode == null || !fieldNode.isObject()) {
            return null;
        }
        JsonNode value = fieldNode.get(key);
        return value != null && !value.isNull() ? value.asText() : null;
    }

    private Double doubleValue(JsonNode fieldNode, String key) {
        if (fieldNode == null || !fieldNode.isObject()) {
            return null;
        }
        JsonNode value = fieldNode.get(key);
        return value != null && value.isNumber() ? value.asDouble() : null;
    }

    private Integer integerValue(JsonNode fieldNode, String key) {
        if (fieldNode == null || !fieldNode.isObject()) {
            return null;
        }
        JsonNode value = fieldNode.get(key);
        return value != null && value.canConvertToInt() ? value.asInt() : null;
    }

    private static final Set<String> STRING_FIELDS = Set.of(
            "legalName",
            "taxCode",
            "businessModel",
            "employeeTier",
            "website",
            "address",
            "companySize",
            "notes"
    );

    private static final Set<String> STRING_LIST_FIELDS = Set.of(
            "industries",
            "markets",
            "targetCustomers",
            "email",
            "phone",
            "strengths",
            "weaknesses",
            "opportunities",
            "threats"
    );

    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "legalName",
            "taxCode",
            "industries",
            "businessModel",
            "products",
            "markets",
            "targetCustomers",
            "employeeTier",
            "website",
            "email",
            "phone",
            "address",
            "companySize",
            "strengths",
            "weaknesses",
            "opportunities",
            "threats"
    );

    private static final Set<String> RISK_STRING_FIELDS = Set.of(
            "legalRisk",
            "financialRisk",
            "reputationRisk",
            "securityRisk",
            "conflictOfInterestRisk",
            "supplyInterruptionRisk",
            "dependencyRisk",
            "overallRiskLevel"
    );

    private static final Set<String> COMPLIANCE_STRING_FIELDS = Set.of(
            "status",
            "antiCorruptionPolicy",
            "laborCompliance",
            "environmentalPolicy"
    );

    private static final Set<String> COMPLIANCE_STRING_LIST_FIELDS = Set.of(
            "qualityCertifications",
            "securityCertifications"
    );
}
