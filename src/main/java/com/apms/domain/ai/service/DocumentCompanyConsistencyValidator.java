package com.apms.domain.ai.service;

import com.apms.domain.ai.dto.DocumentCompanyIdentity;
import com.apms.domain.ai.dto.DocumentCompanyValidationResult;
import com.apms.domain.document.RawDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentCompanyConsistencyValidator {

    private final CompanyIdentityDetectionService identityDetectionService;
    private final CompanyNameNormalizer companyNameNormalizer;

    /**
     * Validate that all documents belong to the same company.
     * @param documents List of RawDocuments to validate
     * @param targetCompanyName Optional target company name from project/task context
     * @param targetTaxCode Optional target company tax code
     */
    public DocumentCompanyValidationResult validate(
            List<RawDocument> documents,
            String targetCompanyName,
            String targetTaxCode) {

        // Single document: no cross-document check needed, but check target if provided
        if (documents.size() <= 1) {
            if (documents.size() == 1 && StringUtils.hasText(targetCompanyName)) {
                DocumentCompanyIdentity identity = identityDetectionService.detectIdentity(documents.get(0));
                return validateAgainstTarget(List.of(identity), targetCompanyName, targetTaxCode);
            }
            return DocumentCompanyValidationResult.valid("Single document", List.of());
        }

        // Detect identity for each document
        List<DocumentCompanyIdentity> identities = new ArrayList<>();
        for (RawDocument doc : documents) {
            DocumentCompanyIdentity identity = identityDetectionService.detectIdentity(doc);
            identities.add(identity);
        }

        log.info("Company identity detection results: {}",
                identities.stream().map(i -> i.getFileName() + " -> " + i.getLegalName() + " [" + i.getStatus() + "]").collect(Collectors.joining(", ")));

        // Level A: Cross-document consistency
        DocumentCompanyValidationResult crossResult = validateCrossDocumentConsistency(identities);
        if (!crossResult.isValid()) {
            return crossResult;
        }

        // Level B: Target company consistency (if target exists)
        if (StringUtils.hasText(targetCompanyName)) {
            return validateAgainstTarget(identities, targetCompanyName, targetTaxCode);
        }

        return crossResult;
    }

    private DocumentCompanyValidationResult validateCrossDocumentConsistency(List<DocumentCompanyIdentity> identities) {
        List<DocumentCompanyIdentity> resolved = identities.stream()
                .filter(i -> "RESOLVED".equals(i.getStatus())).collect(Collectors.toList());
        List<DocumentCompanyIdentity> ambiguous = identities.stream()
                .filter(i -> "AMBIGUOUS".equals(i.getStatus())).collect(Collectors.toList());
        List<DocumentCompanyIdentity> unknown = identities.stream()
                .filter(i -> "UNKNOWN".equals(i.getStatus())).collect(Collectors.toList());

        // All unknown: pass with warning (no company info to compare) ONLY IF single document
        if (resolved.isEmpty() && ambiguous.isEmpty()) {
            if (identities.size() > 1) {
                return DocumentCompanyValidationResult.unresolved(
                        "Không thể xác định công ty của tài liệu.",
                        identities, unknown);
            }
            return DocumentCompanyValidationResult.valid("Unknown", identities);
        }

        // All ambiguous, no resolved: return unresolved
        if (resolved.isEmpty()) {
            return DocumentCompanyValidationResult.unresolved(
                    "Không thể xác định công ty của tài liệu.",
                    identities, ambiguous);
        }

        // Compare resolved documents with each other
        List<String> conflicts = new ArrayList<>();
        DocumentCompanyIdentity reference = resolved.get(0);

        for (int i = 1; i < resolved.size(); i++) {
            DocumentCompanyIdentity current = resolved.get(i);

            // Priority 1: Tax code conflict (STRONGEST)
            if (areTaxCodesConflicting(reference.getTaxCode(), current.getTaxCode())) {
                conflicts.add(String.format("%s (MST: %s) ≠ %s (MST: %s)",
                        reference.getFileName(), reference.getTaxCode(),
                        current.getFileName(), current.getTaxCode()));
                continue;
            }

            // Priority 2: Registration number conflict
            if (areStrongIdsConflicting(reference.getRegistrationNumber(), current.getRegistrationNumber())) {
                conflicts.add(String.format("%s (Reg: %s) ≠ %s (Reg: %s)",
                        reference.getFileName(), reference.getRegistrationNumber(),
                        current.getFileName(), current.getRegistrationNumber()));
                continue;
            }

            // Priority 3: Tax codes match -> same company regardless of name
            if (areTaxCodesMatching(reference.getTaxCode(), current.getTaxCode())) {
                continue; // Same tax code = same company
            }

            // Priority 4: Website domain check (supporting signal)
            if (areDomainsConflicting(reference.getWebsiteDomain(), current.getWebsiteDomain())) {
                // Don't block on domain alone, but note it
                log.debug("Different website domains: {} vs {}", reference.getWebsiteDomain(), current.getWebsiteDomain());
            }

            // Priority 5: Name comparison
            if (!areNamesCompatible(reference, current)) {
                String refName = StringUtils.hasText(reference.getLegalName()) ? reference.getLegalName() : reference.getTradeName();
                String curName = StringUtils.hasText(current.getLegalName()) ? current.getLegalName() : current.getTradeName();
                conflicts.add(String.format("%s → %s ≠ %s → %s",
                        reference.getFileName(), refName,
                        current.getFileName(), curName));
            }
        }

        if (!conflicts.isEmpty()) {
            return DocumentCompanyValidationResult.mismatch(
                    "Tài liệu thuộc các công ty khác nhau.",
                    identities, conflicts);
        }

        // Check unknown documents - if there are any unknown documents in a multi-doc selection, we MUST block
        if (!unknown.isEmpty()) {
            return DocumentCompanyValidationResult.unresolved(
                    "Không thể xác định công ty của tài liệu.",
                    identities, unknown);
        }

        String resolvedName = StringUtils.hasText(reference.getLegalName()) ? reference.getLegalName() : reference.getTradeName();
        return DocumentCompanyValidationResult.valid(resolvedName, identities);
    }

    private DocumentCompanyValidationResult validateAgainstTarget(
            List<DocumentCompanyIdentity> identities, String targetCompanyName, String targetTaxCode) {

        List<String> conflicts = new ArrayList<>();

        for (DocumentCompanyIdentity identity : identities) {
            if (!"RESOLVED".equals(identity.getStatus())) continue;

            // Tax code check against target
            if (StringUtils.hasText(targetTaxCode) && StringUtils.hasText(identity.getTaxCode())) {
                String normalizedTarget = companyNameNormalizer.normalizeTaxCode(targetTaxCode);
                String normalizedDoc = companyNameNormalizer.normalizeTaxCode(identity.getTaxCode());
                if (!normalizedTarget.equals(normalizedDoc)) {
                    conflicts.add(String.format("%s (MST: %s) ≠ Target (MST: %s)",
                            identity.getFileName(), identity.getTaxCode(), targetTaxCode));
                    continue;
                }
            }

            // Name check against target
            String docName = StringUtils.hasText(identity.getLegalName()) ? identity.getLegalName() : identity.getTradeName();
            if (StringUtils.hasText(docName) && StringUtils.hasText(targetCompanyName)) {
                if (!companyNameNormalizer.isSameCompany(docName, targetCompanyName)) {
                    // Also check trade name
                    if (!StringUtils.hasText(identity.getTradeName()) || !companyNameNormalizer.isSameCompany(identity.getTradeName(), targetCompanyName)) {
                        conflicts.add(String.format("%s → %s ≠ Target: %s",
                                identity.getFileName(), docName, targetCompanyName));
                    }
                }
            }
        }

        if (!conflicts.isEmpty()) {
            return DocumentCompanyValidationResult.targetMismatch(
                    "Tài liệu không thuộc công ty mục tiêu.",
                    identities, conflicts);
        }

        String resolvedName = identities.stream()
                .filter(i -> "RESOLVED".equals(i.getStatus()) && StringUtils.hasText(i.getLegalName()))
                .map(DocumentCompanyIdentity::getLegalName)
                .findFirst().orElse(targetCompanyName);
        return DocumentCompanyValidationResult.valid(resolvedName, identities);
    }

    private boolean areTaxCodesConflicting(String tc1, String tc2) {
        if (!StringUtils.hasText(tc1) || !StringUtils.hasText(tc2)) return false;
        String n1 = companyNameNormalizer.normalizeTaxCode(tc1);
        String n2 = companyNameNormalizer.normalizeTaxCode(tc2);
        return !n1.equals(n2);
    }

    private boolean areTaxCodesMatching(String tc1, String tc2) {
        if (!StringUtils.hasText(tc1) || !StringUtils.hasText(tc2)) return false;
        String n1 = companyNameNormalizer.normalizeTaxCode(tc1);
        String n2 = companyNameNormalizer.normalizeTaxCode(tc2);
        return n1.equals(n2);
    }

    private boolean areStrongIdsConflicting(String id1, String id2) {
        if (!StringUtils.hasText(id1) || !StringUtils.hasText(id2)) return false;
        return !id1.trim().equals(id2.trim());
    }

    private boolean areDomainsConflicting(String d1, String d2) {
        if (!StringUtils.hasText(d1) || !StringUtils.hasText(d2)) return false;
        return !d1.equals(d2);
    }

    private boolean areNamesCompatible(DocumentCompanyIdentity id1, DocumentCompanyIdentity id2) {
        // Try all combinations of legalName/tradeName
        List<String> names1 = new ArrayList<>();
        if (StringUtils.hasText(id1.getLegalName())) names1.add(id1.getLegalName());
        if (StringUtils.hasText(id1.getTradeName())) names1.add(id1.getTradeName());

        List<String> names2 = new ArrayList<>();
        if (StringUtils.hasText(id2.getLegalName())) names2.add(id2.getLegalName());
        if (StringUtils.hasText(id2.getTradeName())) names2.add(id2.getTradeName());

        if (names1.isEmpty() || names2.isEmpty()) return true; // Can't compare - assume compatible

        for (String n1 : names1) {
            for (String n2 : names2) {
                if (companyNameNormalizer.isSameCompany(n1, n2)) {
                    return true;
                }
            }
        }
        return false;
    }
}
