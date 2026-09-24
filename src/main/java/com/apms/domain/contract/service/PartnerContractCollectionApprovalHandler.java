package com.apms.domain.contract.service;

import com.apms.common.enums.SubmissionType;
import com.apms.domain.contract.entity.PartnerContractCollectionSubmissionPayload;
import com.apms.domain.contract.repository.mongo.PartnerContractCollectionSubmissionPayloadRepository;
import com.apms.domain.contract.entity.PartnerContractExtractionDraft;
import com.apms.domain.contract.repository.mongo.PartnerContractExtractionDraftRepository;
import com.apms.domain.contract.enums.ContractExtractionReviewDecision;
import com.apms.domain.contract.enums.ContractExtractionReviewStatus;
import com.apms.domain.contract.enums.ContractExtractionApplicationStatus;
import com.apms.domain.contract.enums.ContractReviewStatus;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.project.service.ProjectTaskSubmissionApprovalHandler;
import com.apms.common.exception.BusinessValidationException;
import lombok.RequiredArgsConstructor;
import com.apms.domain.contract.entity.PartnerContract;
import com.apms.domain.contract.entity.PartnerContractVersion;
import com.apms.domain.contract.entity.PartnerContractClauseVersion;
import com.apms.domain.contract.repository.sql.PartnerContractRepository;
import com.apms.domain.contract.repository.sql.PartnerContractVersionRepository;
import com.apms.domain.contract.repository.sql.PartnerContractClauseVersionRepository;
import com.apms.domain.document.RawDocument;
import com.apms.domain.document.repository.mongo.RawDocumentRepository;
import com.apms.domain.profile.service.OwnerOrganizationService;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PartnerContractCollectionApprovalHandler implements ProjectTaskSubmissionApprovalHandler {

    private final PartnerContractCollectionSubmissionPayloadRepository payloadRepository;
    private final PartnerContractExtractionDraftRepository draftRepository;
    private final PartnerContractRepository contractRepository;
    private final PartnerContractVersionRepository versionRepository;
    private final PartnerContractClauseVersionRepository clauseVersionRepository;
    private final OwnerOrganizationService ownerOrganizationService;
    private final com.apms.domain.audit.service.AuditLogService auditLogService;
    private final RawDocumentRepository rawDocumentRepository;
    private final com.apms.domain.notification.service.NotificationService notificationService;
    private final com.apms.domain.document.service.CompanyDocumentPublisher companyDocumentPublisher;

    @Override
    public boolean supports(SubmissionType type) {
        return type == SubmissionType.PARTNER_CONTRACT_COLLECTION;
    }

    @Override
    public void handleApproval(ProjectTaskSubmission submission, Long reviewerId, String reviewComment) {
        PartnerContractCollectionSubmissionPayload payload = payloadRepository.findBySubmissionId(submission.getId())
                .orElseThrow(() -> new BusinessValidationException("Submission payload not found"));

        if (payload.getRawDocumentIds() != null) {
            String referenceCompanyId = ownerOrganizationService.getOwnerCompanyId();

            for (String rawDocumentId : payload.getRawDocumentIds()) {
                RawDocument rawDocument = rawDocumentRepository.findById(rawDocumentId)
                        .orElseThrow(() -> new BusinessValidationException("RawDocument not found: " + rawDocumentId));

                if (Boolean.TRUE.equals(rawDocument.getIsHidden())) {
                    throw new BusinessValidationException("RawDocument is hidden: " + rawDocumentId);
                }
                if (!String.valueOf(payload.getProjectId()).equals(rawDocument.getProjectId())) {
                    throw new BusinessValidationException("RawDocument project mismatch: " + rawDocumentId);
                }
                if (!String.valueOf(payload.getTaskId()).equals(rawDocument.getTaskId())) {
                    throw new BusinessValidationException("RawDocument task mismatch: " + rawDocumentId);
                }
                if (!isPartnerContractRawDocument(rawDocument)) {
                    throw new BusinessValidationException("RawDocument is not a PARTNER_CONTRACT document: " + rawDocumentId);
                }

                PartnerContract contract = contractRepository.findByRawDocumentIdAndSourceTaskId(rawDocumentId, payload.getTaskId()).orElse(null);
                if (contract != null) {
                    continue;
                }

                String fileName = rawDocument.getSource() != null
                        && org.springframework.util.StringUtils.hasText(rawDocument.getSource().getFileName())
                        ? rawDocument.getSource().getFileName()
                        : "Partner contract " + rawDocumentId;

                LocalDateTime approvedAt = LocalDateTime.now();

                contract = PartnerContract.builder()
                        .referenceCompanyId(referenceCompanyId)
                        .partnerCompanyId(payload.getTargetCompanyProfileId())
                        .sourceProjectId(payload.getProjectId())
                        .sourceTaskId(payload.getTaskId())
                        .sourceSubmissionId(submission.getId())
                        .ownerCompanyProfileId(referenceCompanyId)
                        .rawDocumentId(rawDocumentId)
                        .contractTitle(fileName)
                        .contractType("PARTNER_CONTRACT")
                        .reviewStatus(ContractReviewStatus.APPROVED)
                        .createdByAccountId(submission.getSubmittedByAccount().getId())
                        .approvedByAccountId(reviewerId)
                        .approvedAt(approvedAt)
                        .currentVersion(1)
                        .build();

                contract = contractRepository.save(contract);
                auditLogService.log(reviewerId, com.apms.common.enums.AuditAction.PARTNER_CONTRACT_CREATED, "PartnerContract", contract.getId().toString(), "Contract created from approved uploaded document");

                PartnerContractVersion version = PartnerContractVersion.builder()
                        .contractId(contract.getId())
                        .referenceCompanyId(contract.getReferenceCompanyId())
                        .partnerCompanyId(contract.getPartnerCompanyId())
                        .sourceProjectId(contract.getSourceProjectId())
                        .sourceTaskId(contract.getSourceTaskId())
                        .rawDocumentId(contract.getRawDocumentId())
                        .contractTitle(contract.getContractTitle())
                        .contractType(contract.getContractType())
                        .reviewStatus(contract.getReviewStatus())
                        .lifecycleStatus(contract.getLifecycleStatus())
                        .createdByAccountId(contract.getCreatedByAccountId())
                        .createdAt(LocalDateTime.now())
                        .approvedByAccountId(contract.getApprovedByAccountId())
                        .approvedAt(contract.getApprovedAt())
                        .version(1)
                        .build();

                version = versionRepository.save(version);
                auditLogService.log(reviewerId, com.apms.common.enums.AuditAction.PARTNER_CONTRACT_APPROVED, "PartnerContractVersion", version.getId().toString(), "Contract version 1 created from approved uploaded document");

                companyDocumentPublisher.publishApprovedDocument(
                        payload.getTargetCompanyProfileId(),
                        rawDocumentId,
                        reviewerId,
                        approvedAt,
                        com.apms.domain.document.dto.PublicationContext.builder()
                                .sourceProjectId(String.valueOf(payload.getProjectId()))
                                .sourceTaskId(String.valueOf(payload.getTaskId()))
                                .sourceSubmissionId(String.valueOf(submission.getId()))
                                .documentType("PARTNER_CONTRACT")
                                .description("Approved partner contract")
                                .displayName(fileName)
                                .build()
                );
            }
        }

        if (payload.getContractDraftIds() != null) {
            String referenceCompanyId = ownerOrganizationService.getOwnerCompanyId();

            for (String draftId : payload.getContractDraftIds()) {
                PartnerContractExtractionDraft draft = draftRepository.findById(draftId)
                        .orElseThrow(() -> new BusinessValidationException("Draft not found: " + draftId));
                if (draft.getRawDocumentId() != null) {
                    RawDocument rawDocument = rawDocumentRepository.findById(draft.getRawDocumentId())
                            .orElseThrow(() -> new BusinessValidationException("RawDocument not found: " + draft.getRawDocumentId()));
                    if (!String.valueOf(payload.getProjectId()).equals(rawDocument.getProjectId())) {
                        throw new BusinessValidationException("RawDocument project mismatch: " + draft.getRawDocumentId());
                    }
                    if (!String.valueOf(payload.getTaskId()).equals(rawDocument.getTaskId())) {
                        throw new BusinessValidationException("RawDocument task mismatch: " + draft.getRawDocumentId());
                    }
                    if (!isPartnerContractRawDocument(rawDocument)) {
                        throw new BusinessValidationException("RawDocument is not a PARTNER_CONTRACT document: " + draft.getRawDocumentId());
                    }
                }

                // Finalize ALL clauses to ACCEPT if not set
                if (draft.getClauseCandidates() != null) {
                    draft.getClauseCandidates().forEach(c -> {
                        if (c.getReviewDecision() == null) {
                            c.setReviewDecision(ContractExtractionReviewDecision.ACCEPT);
                        }
                    });
                }
                
                // Finalize ALL fields to ACCEPT if not set
                if (draft.getFieldResults() != null) {
                    draft.getFieldResults().forEach(f -> {
                        if (f.getReviewDecision() == null) {
                            f.setReviewDecision(ContractExtractionReviewDecision.ACCEPT);
                        }
                    });
                }

                draft.setReviewStatus(ContractExtractionReviewStatus.REVIEWED);
                draft.setApplicationStatus(ContractExtractionApplicationStatus.APPLIED_FROZEN); 
                draftRepository.save(draft);

                // Create or find PartnerContract
                PartnerContract contract = contractRepository.findByExtractionDraftId(draft.getId()).orElse(null);
                if (contract == null) {
                    contract = PartnerContract.builder()
                            .referenceCompanyId(referenceCompanyId)
                            .partnerCompanyId(payload.getTargetCompanyProfileId())
                            .sourceProjectId(payload.getProjectId())
                            .sourceTaskId(payload.getTaskId())
                            .sourceSubmissionId(submission.getId())
                            .extractionDraftId(draft.getId())
                            .ownerCompanyProfileId(referenceCompanyId)
                            .rawDocumentId(draft.getRawDocumentId())
                            .reviewStatus(ContractReviewStatus.APPROVED)
                            .createdByAccountId(submission.getSubmittedByAccount().getId())
                            .approvedByAccountId(reviewerId)
                            .approvedAt(LocalDateTime.now())
                            .currentVersion(1)
                            .build();

                    // Apply fields
                    if (draft.getFieldResults() != null) {
                        for (var field : draft.getFieldResults()) {
                            if (field.getReviewDecision() == ContractExtractionReviewDecision.ACCEPT ||
                                field.getReviewDecision() == ContractExtractionReviewDecision.EDIT) {
                                Object val = field.getReviewedValue() != null ? field.getReviewedValue() : field.getNormalizedValue();
                                if (val != null) {
                                    switch (field.getFieldName()) {
                                        case "contractNumber" -> contract.setContractNumber(val.toString());
                                        case "contractTitle" -> contract.setContractTitle(val.toString());
                                        case "contractType" -> contract.setContractType(val.toString());
                                        case "currency" -> contract.setCurrency(val.toString().toUpperCase());
                                        case "signedDate" -> contract.setSignedDate(java.time.LocalDate.parse(val.toString()));
                                        case "effectiveDate" -> contract.setEffectiveDate(java.time.LocalDate.parse(val.toString()));
                                        case "expiryDate" -> contract.setExpiryDate(java.time.LocalDate.parse(val.toString()));
                                        case "totalContractValue" -> contract.setTotalContractValue(new java.math.BigDecimal(val.toString()));
                                    }
                                }
                            }
                        }
                    }

                    contract = contractRepository.save(contract);
                    auditLogService.log(reviewerId, com.apms.common.enums.AuditAction.PARTNER_CONTRACT_CREATED, "PartnerContract", contract.getId().toString(), "Contract created from approved collection");

                    PartnerContractVersion version = PartnerContractVersion.builder()
                            .contractId(contract.getId())
                            .referenceCompanyId(contract.getReferenceCompanyId())
                            .partnerCompanyId(contract.getPartnerCompanyId())
                            .sourceProjectId(contract.getSourceProjectId())
                            .sourceTaskId(contract.getSourceTaskId())
                            .rawDocumentId(contract.getRawDocumentId())
                            .contractNumber(contract.getContractNumber())
                            .contractTitle(contract.getContractTitle())
                            .contractType(contract.getContractType())
                            .reviewStatus(contract.getReviewStatus())
                            .lifecycleStatus(contract.getLifecycleStatus())
                            .signedDate(contract.getSignedDate())
                            .effectiveDate(contract.getEffectiveDate())
                            .expiryDate(contract.getExpiryDate())
                            .currency(contract.getCurrency())
                            .totalContractValue(contract.getTotalContractValue())
                            .createdByAccountId(contract.getCreatedByAccountId())
                            .createdAt(LocalDateTime.now())
                            .approvedByAccountId(contract.getApprovedByAccountId())
                            .approvedAt(contract.getApprovedAt())
                            .version(1)
                            .build();

                    version = versionRepository.save(version);
                    auditLogService.log(reviewerId, com.apms.common.enums.AuditAction.PARTNER_CONTRACT_APPROVED, "PartnerContractVersion", version.getId().toString(), "Contract version 1 created upon approval");

                    if (org.springframework.util.StringUtils.hasText(contract.getRawDocumentId())) {
                        companyDocumentPublisher.publishApprovedDocument(
                                payload.getTargetCompanyProfileId(),
                                contract.getRawDocumentId(),
                                reviewerId,
                                contract.getApprovedAt(),
                                com.apms.domain.document.dto.PublicationContext.builder()
                                        .sourceProjectId(String.valueOf(payload.getProjectId()))
                                        .sourceTaskId(String.valueOf(payload.getTaskId()))
                                        .sourceSubmissionId(String.valueOf(submission.getId()))
                                        .documentType("PARTNER_CONTRACT")
                                        .description("Approved partner contract")
                                        .displayName(contract.getContractTitle())
                                        .build()
                        );
                    }

                    if (draft.getClauseCandidates() != null) {
                        for (var clause : draft.getClauseCandidates()) {
                            if (clause.getReviewDecision() == ContractExtractionReviewDecision.ACCEPT ||
                                clause.getReviewDecision() == ContractExtractionReviewDecision.EDIT) {
                                
                                PartnerContractClauseVersion cv = PartnerContractClauseVersion.builder()
                                        .partnerContractVersionId(version.getId())
                                        .clauseIdentity(clause.getClauseCandidateId())
                                        .clauseType(clause.getClauseType())
                                        .clauseTitle(clause.getClauseTitle())
                                        .effectiveDate(clause.getEffectiveDate())
                                        .expiryDate(clause.getExpiryDate())
                                        .noticePeriodDays(clause.getNoticePeriodDays())
                                        .targetMetricKey(clause.getTargetMetricKey())
                                        .targetValue(clause.getTargetValue())
                                        .targetUnit(clause.getTargetUnit())
                                        .comparator(clause.getComparator())
                                        .measurementPeriod(clause.getMeasurementPeriod())
                                        .penaltyValue(clause.getPenaltyValue())
                                        .penaltyCurrency(clause.getPenaltyCurrency())
                                        .penaltyDescription(clause.getPenaltyDescription())
                                        .sourceRawDocumentId(draft.getRawDocumentId())
                                        .evidenceReference(clause.getEvidenceReferences() != null && !clause.getEvidenceReferences().isEmpty() ? clause.getEvidenceReferences().get(0) : null)
                                        .sourceExcerpt(clause.getSourceExcerpt())
                                        .approvedByAccountId(reviewerId)
                                        .approvedAt(LocalDateTime.now())
                                        .build();
                                clauseVersionRepository.save(cv);
                            }
                        }
                    }
                }
            }
        }
    }

    @Override
    public void handleRejection(ProjectTaskSubmission submission, Long reviewerId, String reviewComment) {
        PartnerContractCollectionSubmissionPayload payload = payloadRepository.findBySubmissionId(submission.getId())
                .orElseThrow(() -> new BusinessValidationException("Submission payload not found"));

        if (payload.getRawDocumentIds() != null) {
            for (String rawDocumentId : payload.getRawDocumentIds()) {
                String fileName = org.springframework.util.StringUtils.hasText(rawDocumentId)
                        ? rawDocumentRepository.findById(rawDocumentId)
                            .map(RawDocument::getSource)
                            .map(RawDocument.Source::getFileName)
                            .filter(org.springframework.util.StringUtils::hasText)
                            .orElse(rawDocumentId)
                        : "Partner contract document";
                notificationService.notifyDocumentRejected(submission, rawDocumentId, fileName, reviewerId, reviewComment);
            }
        }

        if (payload.getContractDraftIds() != null) {
            for (String draftId : payload.getContractDraftIds()) {
                PartnerContractExtractionDraft draft = draftRepository.findById(draftId)
                        .orElseThrow(() -> new BusinessValidationException("Draft not found: " + draftId));

                // Bulk reject fields and clauses
                if (draft.getClauseCandidates() != null) {
                    draft.getClauseCandidates().forEach(c -> {
                        if (c.getReviewDecision() == null) {
                            c.setReviewDecision(ContractExtractionReviewDecision.REJECT);
                        }
                    });
                }
                
                if (draft.getFieldResults() != null) {
                    draft.getFieldResults().forEach(f -> {
                        if (f.getReviewDecision() == null) {
                            f.setReviewDecision(ContractExtractionReviewDecision.REJECT);
                        }
                    });
                }

                draft.setReviewStatus(ContractExtractionReviewStatus.REVIEWED);
                draftRepository.save(draft);

                String rawDocumentId = draft.getRawDocumentId();
                String fileName = org.springframework.util.StringUtils.hasText(rawDocumentId)
                        ? rawDocumentRepository.findById(rawDocumentId)
                            .map(RawDocument::getSource)
                            .map(RawDocument.Source::getFileName)
                            .filter(org.springframework.util.StringUtils::hasText)
                            .orElse(rawDocumentId)
                        : "Document package";
                notificationService.notifyDocumentRejected(submission, rawDocumentId, fileName, reviewerId, reviewComment);
            }
        }
    }

    private boolean isPartnerContractRawDocument(RawDocument rawDocument) {
        return rawDocument != null
                && rawDocument.getSource() != null
                && "PARTNER_CONTRACT".equalsIgnoreCase(rawDocument.getSource().getType());
    }
}
