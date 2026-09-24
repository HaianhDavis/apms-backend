package com.apms.domain.financial.service;

import com.apms.domain.ai.service.CompanyNameNormalizer;
import com.apms.domain.financial.DocumentCompanyValidationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class DocumentCompanyMatcher {

    private final CompanyNameNormalizer companyNameNormalizer;

    public DocumentCompanyValidationStatus evaluateCompanyMatch(String detectedCompanyName, String targetCompanyName) {
        if (!StringUtils.hasText(detectedCompanyName) || !StringUtils.hasText(targetCompanyName)) {
            return DocumentCompanyValidationStatus.UNKNOWN;
        }

        if (companyNameNormalizer.isSameCompany(detectedCompanyName, targetCompanyName)) {
            return DocumentCompanyValidationStatus.MATCH;
        }

        String normDetected = companyNameNormalizer.normalize(detectedCompanyName).toLowerCase();
        String normTarget = companyNameNormalizer.normalize(targetCompanyName).toLowerCase();

        if (normDetected.contains(normTarget) || normTarget.contains(normDetected)) {
            return DocumentCompanyValidationStatus.POSSIBLE_MATCH;
        }

        return DocumentCompanyValidationStatus.MISMATCH;
    }
}
