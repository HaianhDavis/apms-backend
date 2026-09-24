package com.apms.domain.ai.service;

import com.apms.domain.ai.dto.ExtractionFieldResult;
import com.apms.domain.ai.dto.ExtractionQualityMetrics;
import com.apms.domain.ai.dto.ExtractionQualityStatus;
import com.apms.domain.ai.dto.ExtractionReviewStatus;
import com.apms.domain.ai.dto.ExtractionValidationStatus;
import com.apms.domain.ai.dto.StaffFieldReviewStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class AiExtractionQualityService {

    public static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,6}$");
    public static final Pattern URL_PATTERN = Pattern.compile("^(https?://)?([a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,}(/.*)?$");
    public static final Pattern PHONE_PATTERN = Pattern.compile("^[+0-9\\s().-]{7,25}$");

    public static boolean isValidEmail(String email) {
        if (!StringUtils.hasText(email)) return true;
        return EMAIL_PATTERN.matcher(email.trim()).find();
    }

    public static boolean isValidUrl(String url) {
        if (!StringUtils.hasText(url)) return true;
        return URL_PATTERN.matcher(url.trim()).find();
    }

    public static boolean isValidPhone(String phone) {
        if (!StringUtils.hasText(phone)) return true;
        return PHONE_PATTERN.matcher(phone.trim()).find();
    }

    public static final int CANONICAL_FIELD_COUNT = 13;

    public static final java.util.Set<String> EXCLUDED_LEGACY_FIELDS = java.util.Set.of(
            "legalName", "taxCode", "identity.legalName", "identity.taxCode"
    );

    public static String toCanonicalPath(String name) {
        if (name == null || EXCLUDED_LEGACY_FIELDS.contains(name)) return null;
        if (name.equals("tradeName") || name.equals("identity.tradeName")) return "identity.tradeName";
        if (name.equals("website") || name.equals("contact.website")) return "contact.website";
        if (name.equals("addresses") || name.equals("address") || name.equals("contact.addresses") || name.equals("contact.address")) return "contact.addresses";
        if (name.equals("emails") || name.equals("email") || name.equals("contact.emails") || name.equals("contact.email")) return "contact.emails";
        if (name.equals("phones") || name.equals("phone") || name.equals("contact.phones") || name.equals("contact.phone")) return "contact.phones";
        if (name.equals("businessModel") || name.equals("business.businessModel")) return "business.businessModel";
        if (name.equals("industries") || name.equals("business.industries")) return "business.industries";
        if (name.equals("foundedYear") || name.equals("business.foundedYear")) return "business.foundedYear";
        if (name.equals("employeeCount") || name.equals("companySize.employeeCount")) return "companySize.employeeCount";
        if (name.equals("companyDescription") || name.equals("description") || name.equals("business.companyDescription")) return "business.companyDescription";
        if (name.equals("markets") || name.equals("business.markets")) return "business.markets";
        if (name.equals("targetCustomers") || name.equals("business.targetCustomers")) return "business.targetCustomers";
        if (name.equals("products") || name.equals("business.products")) return "business.products";
        return null;
    }

    public void validateExtraction(Map<String, ExtractionFieldResult> fieldResults) {
        if (fieldResults == null) return;

        for (Map.Entry<String, ExtractionFieldResult> entry : fieldResults.entrySet()) {
            if (EXCLUDED_LEGACY_FIELDS.contains(entry.getKey())) continue;
            validateField(entry.getKey(), entry.getValue());
        }
    }

    private void validateField(String fieldName, ExtractionFieldResult result) {
        if (isProjectProvidedIdentityField(fieldName, result)) {
            result.setValidationStatus(ExtractionValidationStatus.PASS);
            if (!StringUtils.hasText(result.getValidationMessages())) {
                result.setValidationMessages("Provided by manager at project creation.");
            }
            return;
        }

        if (result == null || result.getValue() == null) {
            result.setValidationStatus(ExtractionValidationStatus.PASS);
            result.setValidationMessages("Field is empty.");
            return;
        }

        String valueStr = result.getValue().toString();
        if (!StringUtils.hasText(valueStr) || valueStr.equals("[]")) {
            result.setValidationStatus(ExtractionValidationStatus.PASS);
            return;
        }

        // 1. Evidence Check
        boolean hasEvidence = StringUtils.hasText(result.getEvidenceText());

        if (!hasEvidence) {
            // No evidence for a non-null field is a WARNING or FAIL
            if (isCriticalIdentityField(fieldName)) {
                result.setValidationStatus(ExtractionValidationStatus.FAIL);
                result.setValidationMessages("Critical field lacks evidence in the document.");
            } else {
                result.setValidationStatus(ExtractionValidationStatus.WARNING);
                result.setValidationMessages("Field has value but no supporting evidence.");
            }
            return;
        }

        // 2. Format Checks
        if (fieldName.equals("email") || fieldName.equals("emails") || fieldName.endsWith(".emails") || fieldName.endsWith(".email")) {
            boolean invalid = false;
            if (result.getValue() instanceof java.util.List<?> list) {
                for (Object item : list) {
                    if (item != null && !EMAIL_PATTERN.matcher(item.toString()).find()) invalid = true;
                }
            } else {
                if (!EMAIL_PATTERN.matcher(valueStr).find()) invalid = true;
            }
            if (invalid) {
                result.setValidationStatus(ExtractionValidationStatus.FAIL);
                result.setValidationMessages("Invalid email format.");
                return;
            }
        }

        if ((fieldName.equals("website") || fieldName.endsWith(".website")) && !URL_PATTERN.matcher(valueStr).find()) {
            result.setValidationStatus(ExtractionValidationStatus.FAIL);
            result.setValidationMessages("Invalid website URL format.");
            return;
        }

        // 3. Confidence Check
        if (result.getConfidence() != null && result.getConfidence() < 0.5) {
            result.setValidationStatus(ExtractionValidationStatus.WARNING);
            result.setValidationMessages("Low confidence score (" + result.getConfidence() + ").");
            return;
        }

        result.setValidationStatus(ExtractionValidationStatus.PASS);
    }

    private boolean isCriticalIdentityField(String fieldName) {
        return "tradeName".equals(fieldName) || "identity.tradeName".equals(fieldName);
    }

    public ExtractionQualityMetrics computeMetrics(Map<String, ExtractionFieldResult> fieldResults) {
        ExtractionQualityMetrics metrics = new ExtractionQualityMetrics();
        if (fieldResults == null || fieldResults.isEmpty()) return metrics;

        Map<String, ExtractionFieldResult> canonicalMap = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, ExtractionFieldResult> entry : fieldResults.entrySet()) {
            String key = entry.getKey();
            ExtractionFieldResult res = entry.getValue();
            String canonical = toCanonicalPath(key);
            if (canonical == null && res != null && res.getFieldName() != null) {
                canonical = toCanonicalPath(res.getFieldName());
            }
            if (canonical != null && res != null) {
                canonicalMap.putIfAbsent(canonical, res);
            }
        }

        if (canonicalMap.isEmpty()) {
            return metrics;
        }

        int total = CANONICAL_FIELD_COUNT;
        int withValue = 0;
        int withEvidence = 0;
        int passed = 0;
        int warning = 0;
        int failed = 0;
        int hallucinationRisk = 0;
        double sumConfidence = 0.0;
        int confidenceCount = 0;

        for (ExtractionFieldResult res : canonicalMap.values()) {
            boolean hasVal = res.getValue() != null && StringUtils.hasText(res.getValue().toString()) && !res.getValue().toString().equals("[]");
            boolean isProjectProvided = isProjectProvidedIdentityField(res.getFieldName(), res);
            if (hasVal) {
                withValue++;
                if (StringUtils.hasText(res.getEvidenceText()) || isProjectProvided) {
                    withEvidence++;
                } else {
                    hallucinationRisk++;
                }

                if (res.getConfidence() != null) {
                    sumConfidence += res.getConfidence();
                    confidenceCount++;
                }
            }

            if (res.getValidationStatus() == ExtractionValidationStatus.PASS) passed++;
            if (res.getValidationStatus() == ExtractionValidationStatus.WARNING) warning++;
            if (res.getValidationStatus() == ExtractionValidationStatus.FAIL) failed++;
        }

        metrics.setTotalFields(total);
        metrics.setFieldsWithValue(withValue);
        metrics.setFieldsWithEvidence(withEvidence);
        metrics.setPassedFields(passed);
        metrics.setWarningFields(warning);
        metrics.setFailedFields(failed);
        metrics.setHallucinationRiskCount(hallucinationRisk);

        if (confidenceCount > 0) {
            metrics.setAverageConfidence(sumConfidence / confidenceCount);
        }

        if (withValue > 0) {
            metrics.setEvidenceCoverageRate((double) withEvidence / withValue);
        }

        // Count required fields completeness (tradeName, industries, description)
        int required = 3;
        int requiredFound = 0;
        if (hasValue(canonicalMap.get("identity.tradeName"))) requiredFound++;
        if (hasValue(canonicalMap.get("business.industries"))) requiredFound++;
        if (hasValue(canonicalMap.get("business.companyDescription"))) requiredFound++;

        metrics.setCompletenessRate((double) requiredFound / required);

        return metrics;
    }

    private boolean hasValue(ExtractionFieldResult r) {
        return r != null && r.getValue() != null && StringUtils.hasText(r.getValue().toString()) && !r.getValue().toString().equals("[]");
    }

    private boolean isProjectProvidedIdentityField(String fieldName, ExtractionFieldResult result) {
        if (result == null || !isCriticalIdentityField(fieldName)) {
            return false;
        }
        return result.getStaffReviewStatus() == StaffFieldReviewStatus.CONFIRMED
                && result.getManagerReviewStatus() == ExtractionReviewStatus.ACCEPTED
                && StringUtils.hasText(result.getValidationMessages())
                && result.getValidationMessages().startsWith("Provided by manager");
    }

    public ExtractionQualityStatus determineOverallStatus(ExtractionQualityMetrics metrics) {
        if (metrics.getFailedFields() > 0) {
            return ExtractionQualityStatus.NEEDS_REVIEW;
        }
        if (metrics.getWarningFields() > 0) {
            return ExtractionQualityStatus.VALIDATED; // Requires review but is technically "validated"
        }
        return ExtractionQualityStatus.VALIDATED;
    }
}
