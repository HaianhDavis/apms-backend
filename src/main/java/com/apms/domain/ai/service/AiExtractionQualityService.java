package com.apms.domain.ai.service;

import com.apms.domain.ai.dto.ExtractionFieldResult;
import com.apms.domain.ai.dto.ExtractionQualityMetrics;
import com.apms.domain.ai.dto.ExtractionQualityStatus;
import com.apms.domain.ai.dto.ExtractionValidationStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.regex.Pattern;

@Service
public class AiExtractionQualityService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,6}$");
    private static final Pattern URL_PATTERN = Pattern.compile("^(https?://)?([a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,}(/.*)?$");
    
    public void validateExtraction(Map<String, ExtractionFieldResult> fieldResults) {
        if (fieldResults == null) return;
        
        for (Map.Entry<String, ExtractionFieldResult> entry : fieldResults.entrySet()) {
            validateField(entry.getKey(), entry.getValue());
        }
    }
    
    private void validateField(String fieldName, ExtractionFieldResult result) {
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
        if (fieldName.equals("email") && !EMAIL_PATTERN.matcher(valueStr).find()) {
            result.setValidationStatus(ExtractionValidationStatus.FAIL);
            result.setValidationMessages("Invalid email format.");
            return;
        }
        
        if (fieldName.equals("website") && !URL_PATTERN.matcher(valueStr).find()) {
            result.setValidationStatus(ExtractionValidationStatus.FAIL);
            result.setValidationMessages("Invalid website URL format.");
            return;
        }
        
        if (fieldName.equals("taxCode") && valueStr.length() < 5) {
            result.setValidationStatus(ExtractionValidationStatus.FAIL);
            result.setValidationMessages("Suspiciously short tax code.");
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
        return "legalName".equals(fieldName) || "taxCode".equals(fieldName);
    }
    
    public ExtractionQualityMetrics computeMetrics(Map<String, ExtractionFieldResult> fieldResults) {
        ExtractionQualityMetrics metrics = new ExtractionQualityMetrics();
        if (fieldResults == null || fieldResults.isEmpty()) return metrics;
        
        int total = fieldResults.size();
        int withValue = 0;
        int withEvidence = 0;
        int passed = 0;
        int warning = 0;
        int failed = 0;
        int hallucinationRisk = 0;
        double sumConfidence = 0.0;
        int confidenceCount = 0;
        
        for (ExtractionFieldResult res : fieldResults.values()) {
            boolean hasVal = res.getValue() != null && StringUtils.hasText(res.getValue().toString()) && !res.getValue().toString().equals("[]");
            if (hasVal) {
                withValue++;
                if (StringUtils.hasText(res.getEvidenceText())) {
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
        
        // Count required fields completeness (legalName, industry, description)
        int required = 3;
        int requiredFound = 0;
        if (hasValue(fieldResults.get("legalName"))) requiredFound++;
        if (hasValue(fieldResults.get("industries"))) requiredFound++;
        if (hasValue(fieldResults.get("description"))) requiredFound++;
        
        metrics.setCompletenessRate((double) requiredFound / required);
        
        return metrics;
    }
    
    private boolean hasValue(ExtractionFieldResult r) {
        return r != null && r.getValue() != null && StringUtils.hasText(r.getValue().toString()) && !r.getValue().toString().equals("[]");
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
