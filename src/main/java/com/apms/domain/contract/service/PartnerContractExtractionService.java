package com.apms.domain.contract.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.ai.service.provider.PartnerContractExtractionProvider;
import com.apms.domain.contract.config.ContractExtractionProperties;
import com.apms.domain.contract.dto.ApplyExtractionRequest;
import com.apms.domain.contract.dto.ContractDocumentSegment;
import com.apms.domain.contract.dto.ReviewExtractionClauseRequest;
import com.apms.domain.contract.dto.PartnerContractExtractionOutput;
import com.apms.domain.contract.entity.PartnerContract;
import com.apms.domain.contract.entity.PartnerContractExtractionDraft;
import com.apms.domain.contract.entity.PartnerContractExtractionDraft.ClauseCandidate;
import com.apms.domain.contract.entity.PartnerContractExtractionDraft.ContractExtractionFieldResult;
import com.apms.domain.contract.entity.PartnerContractVersion;
import com.apms.domain.contract.enums.*;
import com.apms.domain.contract.repository.mongo.PartnerContractExtractionDraftRepository;
import com.apms.domain.contract.repository.sql.PartnerContractRepository;
import com.apms.domain.contract.repository.sql.PartnerContractVersionRepository;
import com.apms.domain.document.RawDocument;
import com.apms.domain.document.repository.mongo.RawDocumentRepository;
import com.apms.domain.project.repository.sql.ProjectRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class PartnerContractExtractionService {

    private final PartnerContractExtractionDraftRepository draftRepository;
    private final PartnerContractExtractionProvider extractionProvider;
    private final PartnerContractRepository partnerContractRepository;
    private final PartnerContractVersionRepository versionRepository;
    private final RawDocumentRepository rawDocumentRepository;
    private final AuditLogService auditService;
    private final ProjectRepository projectRepository;
    private final com.apms.domain.project.repository.sql.ProjectTaskRepository projectTaskRepository;
    private final ContractExtractionProperties config;

    public PartnerContractExtractionService(PartnerContractExtractionDraftRepository draftRepository,
                                            PartnerContractExtractionProvider extractionProvider,
                                            PartnerContractRepository partnerContractRepository,
                                            PartnerContractVersionRepository versionRepository,
                                            RawDocumentRepository rawDocumentRepository,
                                            AuditLogService auditService,
                                            ProjectRepository projectRepository,
                                            com.apms.domain.project.repository.sql.ProjectTaskRepository projectTaskRepository,
                                            ContractExtractionProperties config) {
        this.draftRepository = draftRepository;
        this.extractionProvider = extractionProvider;
        this.partnerContractRepository = partnerContractRepository;
        this.versionRepository = versionRepository;
        this.rawDocumentRepository = rawDocumentRepository;
        this.auditService = auditService;
        this.projectRepository = projectRepository;
        this.projectTaskRepository = projectTaskRepository;
        this.config = config;
    }

    private void validateProjectAccess(Long projectId, Long accountId, boolean isWrite) {
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof com.apms.security.UserDetailsImpl) {
            com.apms.security.UserDetailsImpl user = (com.apms.security.UserDetailsImpl) auth.getPrincipal();
            boolean isAdmin = user.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SYSTEM_ADMIN"));
            if (isAdmin) return;

            if (!isWrite) {
                boolean isOwner = user.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_BUSINESS_OWNER"));
                if (isOwner) return;
            }
        }

        if (!projectRepository.existsByIdAndMembersAccountId(projectId, accountId)) {
            throw new org.springframework.security.access.AccessDeniedException("Must be a project member to access this contract.");
        }
    }

    @Transactional
    public PartnerContractExtractionDraft generateExtraction(Long contractId, Long accountId) {
        PartnerContract contract = partnerContractRepository.findById(contractId)
                .orElseThrow(() -> new BusinessValidationException("PartnerContract not found: " + contractId));

        validateProjectAccess(contract.getSourceProjectId(), accountId, true);

        if (contract.getReviewStatus() == ContractReviewStatus.APPROVED ||
            contract.getReviewStatus() == ContractReviewStatus.IN_REVIEW) {
            throw new BusinessValidationException("Contract must be in DRAFT or CHANGES_REQUESTED to generate extraction.");
        }

        if (contract.getRawDocumentId() == null) {
            throw new BusinessValidationException("No RawDocument linked to this contract.");
        }

        RawDocument doc = rawDocumentRepository.findById(contract.getRawDocumentId())
                .orElseThrow(() -> new BusinessValidationException("RawDocument not found: " + contract.getRawDocumentId()));

        if (doc.getIsHidden() != null && doc.getIsHidden()) {
            throw new BusinessValidationException("RawDocument is hidden.");
        }

        if (!String.valueOf(contract.getSourceProjectId()).equals(doc.getProjectId())) {
             throw new BusinessValidationException("RawDocument project mismatch.");
        }

        if (contract.getSourceTaskId() != null && doc.getTaskId() != null &&
            !String.valueOf(contract.getSourceTaskId()).equals(doc.getTaskId())) {
             throw new BusinessValidationException("RawDocument task mismatch.");
        }

        String sourceText = extractText(doc);
        if (sourceText == null || sourceText.trim().isEmpty()) {
            throw new BusinessValidationException("No text available for extraction.");
        }

        String docHash = doc.getStorage() != null && doc.getStorage().getChecksum() != null ?
                doc.getStorage().getChecksum() : DigestUtils.md5DigestAsHex(sourceText.getBytes());

        List<ContractExtractionFieldResult> allFields = new ArrayList<>();
        List<ClauseCandidate> allClauses = new ArrayList<>();
        List<String> allWarnings = new ArrayList<>();

        ContractExtractionGenerationStatus status = ContractExtractionGenerationStatus.COMPLETED;
        ContractExtractionQualityStatus quality = ContractExtractionQualityStatus.PASS;

        int totalLength = sourceText.length();
        int offset = 0;
        int processedLength = 0;
        int segmentCount = 0;
        int skippedSegments = 0;
        boolean clauseLimitHit = false;
        List<ContractDocumentSegment> segments = new ArrayList<>();

        int segmentChars = config.getSegmentChars();
        int overlapChars = config.getSegmentOverlapChars();
        int maxSegments = config.getMaxSegments();
        int maxTotalChars = config.getMaxTotalChars();
        int maxClauses = config.getMaxClauses();

        while (offset < totalLength) {
            // Enforce max-total-chars
            if (processedLength >= maxTotalChars) {
                status = ContractExtractionGenerationStatus.PARTIAL;
                quality = ContractExtractionQualityStatus.WARNING;
                int remainingChars = totalLength - offset;
                skippedSegments = (int) Math.ceil((double) remainingChars / segmentChars);
                allWarnings.add("Document exceeded max-total-chars (" + maxTotalChars + "). " + skippedSegments + " segments were skipped.");
                break;
            }

            // Enforce max-segments
            if (segmentCount >= maxSegments) {
                status = ContractExtractionGenerationStatus.PARTIAL;
                quality = ContractExtractionQualityStatus.WARNING;
                int remainingChars = totalLength - offset;
                skippedSegments = (int) Math.ceil((double) remainingChars / segmentChars);
                allWarnings.add("Document exceeded max-segments (" + maxSegments + "). " + skippedSegments + " segments were skipped.");
                break;
            }

            // Enforce max-clauses
            if (allClauses.size() >= maxClauses) {
                status = ContractExtractionGenerationStatus.PARTIAL;
                quality = ContractExtractionQualityStatus.WARNING;
                clauseLimitHit = true;
                int remainingChars = totalLength - offset;
                skippedSegments = (int) Math.ceil((double) remainingChars / segmentChars);
                allWarnings.add("Extraction exceeded max-clauses (" + maxClauses + "). " + skippedSegments + " segments were skipped.");
                break;
            }

            int endOffset = Math.min(offset + segmentChars, totalLength);
            String chunk = sourceText.substring(offset, endOffset);

            try {
                String segmentId = generateSegmentId(docHash, offset, endOffset);
                String excerptHash = DigestUtils.md5DigestAsHex(chunk.getBytes());

                ContractDocumentSegment segment = ContractDocumentSegment.builder()
                    .segmentId(segmentId)
                    .rawDocumentId(doc.getId())
                    .sourceDocumentHash(docHash)
                    .startOffset(offset)
                    .endOffset(endOffset)
                    .excerpt(chunk)
                    .excerptHash(excerptHash)
                    .build();
                segments.add(segment);
                segmentCount++;

                PartnerContractExtractionOutput output = extractionProvider.extractContract(chunk);
                if (output.getMetadataFields() != null) {
                    output.getMetadataFields().forEach(f -> {
                        f.setEvidenceReferences(List.of(segmentId));
                        allFields.add(f);
                    });
                }
                if (output.getClauseCandidates() != null) {
                    output.getClauseCandidates().forEach(c -> {
                        c.setEvidenceReferences(List.of(segmentId));
                        allClauses.add(c);
                    });
                }
                if (output.getWarnings() != null) allWarnings.addAll(output.getWarnings());
            } catch (Exception e) {
                log.error("Failed to extract chunk at offset {}", offset, e);
                status = ContractExtractionGenerationStatus.PARTIAL;
                quality = ContractExtractionQualityStatus.WARNING;
                allWarnings.add("Failed to process segment at offset " + offset);
            }

            processedLength += chunk.length();
            // Advance by segmentChars minus overlap to keep cross-boundary context
            int advance = segmentChars - overlapChars;
            if (advance <= 0) advance = segmentChars; // safety: prevent infinite loop
            offset += advance;
        }

        Integer currentApprovedVersion = 0;
        Optional<PartnerContractVersion> lastVersion = versionRepository.findTopByContractIdOrderByVersionDesc(contractId);
        if (lastVersion.isPresent()) {
             currentApprovedVersion = lastVersion.get().getVersion();
        }

        PartnerContractExtractionDraft draft = PartnerContractExtractionDraft.builder()
                .partnerContractId(contractId)
                .rawDocumentId(doc.getId())
                .sourceProjectId(contract.getSourceProjectId())
                .sourceTaskId(contract.getSourceTaskId())
                .sourceDocumentHash(docHash)
                .contractVersionAtGeneration(contract.getVersion())
                .currentApprovedVersion(currentApprovedVersion)
                .expectedNextApprovalVersion(currentApprovedVersion + 1)
                .generationStatus(status)
                .qualityStatus(quality)
                .reviewStatus(ContractExtractionReviewStatus.PENDING)
                .applicationStatus(ContractExtractionApplicationStatus.NOT_APPLIED)
                .approvalSyncStatus(ContractExtractionApprovalSyncStatus.NOT_REQUIRED)
                .generatedAt(LocalDateTime.now())
                .generatedByAccountId(accountId)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .fieldResults(allFields)
                .clauseCandidates(allClauses)
                .warnings(allWarnings)
                .segments(segments)
                .build();

        draft = draftRepository.save(draft);
        auditService.log(accountId, AuditAction.PARTNER_CONTRACT_EXTRACTION_STARTED, "PartnerContract", contractId.toString(), "Generated extraction draft: " + draft.getId());
        return draft;
    }

    @Transactional
    public PartnerContractExtractionDraft generateExtractionForTask(Long taskId, String rawDocumentId, Long accountId) {
        com.apms.domain.project.ProjectTask task = projectTaskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessValidationException("Task not found: " + taskId));

        validateProjectAccess(task.getProject().getId(), accountId, true);

        if (task.getTaskType() != com.apms.common.enums.TaskType.PARTNER_CONTRACT_COLLECTION) {
            throw new BusinessValidationException("Task is not a PARTNER_CONTRACT_COLLECTION task");
        }

        RawDocument doc = rawDocumentRepository.findById(rawDocumentId)
                .orElseThrow(() -> new BusinessValidationException("RawDocument not found: " + rawDocumentId));

        if (doc.getIsHidden() != null && doc.getIsHidden()) {
            throw new BusinessValidationException("RawDocument is hidden.");
        }

        if (!String.valueOf(task.getProject().getId()).equals(doc.getProjectId())) {
            throw new BusinessValidationException("RawDocument project mismatch.");
        }

        if (doc.getTaskId() != null && !String.valueOf(taskId).equals(doc.getTaskId())) {
            throw new BusinessValidationException("RawDocument task mismatch.");
        }

        String sourceText = extractText(doc);
        if (sourceText == null || sourceText.trim().isEmpty()) {
            throw new BusinessValidationException("No text available for extraction.");
        }

        String docHash = doc.getStorage() != null && doc.getStorage().getChecksum() != null ?
                doc.getStorage().getChecksum() : DigestUtils.md5DigestAsHex(sourceText.getBytes());

        List<ContractExtractionFieldResult> allFields = new ArrayList<>();
        List<ClauseCandidate> allClauses = new ArrayList<>();
        List<String> allWarnings = new ArrayList<>();

        ContractExtractionGenerationStatus status = ContractExtractionGenerationStatus.COMPLETED;
        ContractExtractionQualityStatus quality = ContractExtractionQualityStatus.PASS;

        int totalLength = sourceText.length();
        int offset = 0;
        int processedLength = 0;
        int segmentCount = 0;
        int skippedSegments = 0;
        boolean clauseLimitHit = false;
        List<ContractDocumentSegment> segments = new ArrayList<>();

        int segmentChars = config.getSegmentChars();
        int overlapChars = config.getSegmentOverlapChars();
        int maxSegments = config.getMaxSegments();
        int maxTotalChars = config.getMaxTotalChars();
        int maxClauses = config.getMaxClauses();

        while (offset < totalLength) {
            if (processedLength >= maxTotalChars) {
                status = ContractExtractionGenerationStatus.PARTIAL;
                quality = ContractExtractionQualityStatus.WARNING;
                int remainingChars = totalLength - offset;
                skippedSegments = (int) Math.ceil((double) remainingChars / segmentChars);
                allWarnings.add("Document exceeded max-total-chars (" + maxTotalChars + "). " + skippedSegments + " segments were skipped.");
                break;
            }

            if (segmentCount >= maxSegments) {
                status = ContractExtractionGenerationStatus.PARTIAL;
                quality = ContractExtractionQualityStatus.WARNING;
                int remainingChars = totalLength - offset;
                skippedSegments = (int) Math.ceil((double) remainingChars / segmentChars);
                allWarnings.add("Document exceeded max-segments (" + maxSegments + "). " + skippedSegments + " segments were skipped.");
                break;
            }

            if (allClauses.size() >= maxClauses) {
                status = ContractExtractionGenerationStatus.PARTIAL;
                quality = ContractExtractionQualityStatus.WARNING;
                clauseLimitHit = true;
                int remainingChars = totalLength - offset;
                skippedSegments = (int) Math.ceil((double) remainingChars / segmentChars);
                allWarnings.add("Extraction exceeded max-clauses (" + maxClauses + "). " + skippedSegments + " segments were skipped.");
                break;
            }

            int endOffset = Math.min(offset + segmentChars, totalLength);
            String chunk = sourceText.substring(offset, endOffset);

            try {
                String segmentId = generateSegmentId(docHash, offset, endOffset);
                String excerptHash = DigestUtils.md5DigestAsHex(chunk.getBytes());

                ContractDocumentSegment segment = ContractDocumentSegment.builder()
                        .segmentId(segmentId)
                        .rawDocumentId(doc.getId())
                        .sourceDocumentHash(docHash)
                        .startOffset(offset)
                        .endOffset(endOffset)
                        .excerpt(chunk)
                        .excerptHash(excerptHash)
                        .build();
                segments.add(segment);
                segmentCount++;

                PartnerContractExtractionOutput output = extractionProvider.extractContract(chunk);
                if (output.getMetadataFields() != null) {
                    output.getMetadataFields().forEach(f -> {
                        f.setEvidenceReferences(List.of(segmentId));
                        allFields.add(f);
                    });
                }
                if (output.getClauseCandidates() != null) {
                    output.getClauseCandidates().forEach(c -> {
                        c.setEvidenceReferences(List.of(segmentId));
                        allClauses.add(c);
                    });
                }
                if (output.getWarnings() != null) allWarnings.addAll(output.getWarnings());
            } catch (Exception e) {
                log.error("Failed to extract chunk at offset {}", offset, e);
                status = ContractExtractionGenerationStatus.PARTIAL;
                quality = ContractExtractionQualityStatus.WARNING;
                allWarnings.add("Failed to process segment at offset " + offset);
            }

            processedLength += chunk.length();
            int advance = segmentChars - overlapChars;
            if (advance <= 0) advance = segmentChars;
            offset += advance;
        }

        PartnerContractExtractionDraft draft = PartnerContractExtractionDraft.builder()
                .purpose(ContractExtractionPurpose.PARTNER_CONTRACT_COLLECTION)
                .sourceProjectId(task.getProject().getId())
                .sourceTaskId(taskId)
                .targetCompanyProfileId(task.getTargetCompanyProfileId())
                .ownerCompanyProfileId(doc.getOwnerCompanyProfileId())
                .rawDocumentId(doc.getId())
                .sourceDocumentHash(docHash)
                .generationStatus(status)
                .qualityStatus(quality)
                .reviewStatus(ContractExtractionReviewStatus.PENDING)
                .applicationStatus(ContractExtractionApplicationStatus.NOT_APPLIED)
                .approvalSyncStatus(ContractExtractionApprovalSyncStatus.NOT_REQUIRED)
                .generatedAt(LocalDateTime.now())
                .generatedByAccountId(accountId)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .fieldResults(allFields)
                .clauseCandidates(allClauses)
                .warnings(allWarnings)
                .segments(segments)
                .build();

        draft = draftRepository.save(draft);
        auditService.log(accountId, AuditAction.PARTNER_CONTRACT_EXTRACTION_STARTED, "PartnerContractExtractionDraft", draft.getId(), "Generated task-level extraction draft: " + draft.getId());
        return draft;
    }

    @Transactional
    public PartnerContractExtractionDraft reviewField(Long contractId, String extractionId, String fieldKey, com.apms.domain.contract.dto.ReviewExtractionFieldRequest request, Long accountId) {
        PartnerContractExtractionDraft draft = getDraftForContract(extractionId, contractId);
        validateProjectAccess(draft.getSourceProjectId(), accountId, true);
        ensureDraftIsEditable(draft);

        ContractExtractionFieldResult fieldResult = draft.getFieldResults().stream()
                .filter(f -> f.getFieldName().equals(fieldKey))
                .findFirst()
                .orElseThrow(() -> new BusinessValidationException("Field not found: " + fieldKey));

        if (request.getReviewDecision() == ContractExtractionReviewDecision.EDIT && request.getReviewedValue() == null) {
            throw new BusinessValidationException("EDIT decision requires a reviewedValue");
        }

        fieldResult.setReviewDecision(request.getReviewDecision());
        fieldResult.setReviewedValue(request.getReviewedValue());
        fieldResult.setReviewComment(request.getReviewComment());
        fieldResult.setReviewedByAccountId(accountId);
        fieldResult.setReviewedAt(LocalDateTime.now());

        updateDraftReviewStatus(draft);
        draft.setUpdatedAt(LocalDateTime.now());
        draft = draftRepository.save(draft);
        auditService.log(accountId, AuditAction.PARTNER_CONTRACT_EXTRACTION_FIELD_REVIEWED, "PartnerContractExtractionDraft", extractionId, "Reviewed field: " + fieldKey);
        return draft;
    }

    @Transactional
    public PartnerContractExtractionDraft reviewClause(Long contractId, String extractionId, String clauseCandidateId, com.apms.domain.contract.dto.ReviewExtractionClauseRequest request, Long accountId) {
        PartnerContractExtractionDraft draft = getDraftForContract(extractionId, contractId);
        validateProjectAccess(draft.getSourceProjectId(), accountId, true);
        ensureDraftIsEditable(draft);

        ClauseCandidate clause = draft.getClauseCandidates().stream()
                .filter(c -> c.getClauseCandidateId().equals(clauseCandidateId))
                .findFirst()
                .orElseThrow(() -> new BusinessValidationException("Clause not found: " + clauseCandidateId));

        clause.setReviewDecision(request.getReviewDecision());
        clause.setReviewComment(request.getReviewComment());

        if (request.getReviewDecision() == ContractExtractionReviewDecision.EDIT) {
            if (!StringUtils.hasText(request.getReviewComment())) {
                throw new BusinessValidationException("EDIT decision requires a reviewComment");
            }
            clause.setClauseTitle(request.getClauseTitle());
            clause.setClauseType(request.getClauseType());
            clause.setNormalizedTerms(request.getNormalizedTerms());
            clause.setEffectiveDate(request.getEffectiveDate());
            clause.setExpiryDate(request.getExpiryDate());
            clause.setNoticePeriodDays(request.getNoticePeriodDays());
            clause.setTargetMetricKey(request.getTargetMetricKey());
            clause.setTargetValue(request.getTargetValue());
            clause.setTargetUnit(request.getTargetUnit());
            clause.setComparator(request.getComparator());
            clause.setMeasurementPeriod(request.getMeasurementPeriod());
            clause.setPenaltyValue(request.getPenaltyValue());
            clause.setPenaltyCurrency(request.getPenaltyCurrency());
            clause.setPenaltyDescription(request.getPenaltyDescription());
            clause.setReviewedFields(request.getReviewedFields());
        }

        clause.setReviewedByAccountId(accountId);
        clause.setReviewedAt(LocalDateTime.now());

        updateDraftReviewStatus(draft);
        draft.setUpdatedAt(LocalDateTime.now());
        draft = draftRepository.save(draft);
        auditService.log(accountId, AuditAction.PARTNER_CONTRACT_EXTRACTION_CLAUSE_REVIEWED, "PartnerContractExtractionDraft", extractionId, "Reviewed clause: " + clauseCandidateId);
        return draft;
    }

    @Transactional
    public PartnerContractExtractionDraft reviewFieldByTask(Long taskId, String extractionId, String fieldKey, com.apms.domain.contract.dto.ReviewExtractionFieldRequest request, Long accountId) {
        PartnerContractExtractionDraft draft = getDraftForTask(extractionId, taskId);
        validateProjectAccess(draft.getSourceProjectId(), accountId, true);
        ensureDraftIsEditable(draft);

        ContractExtractionFieldResult fieldResult = draft.getFieldResults().stream()
                .filter(f -> f.getFieldName().equals(fieldKey))
                .findFirst()
                .orElseThrow(() -> new BusinessValidationException("Field not found: " + fieldKey));

        if (request.getReviewDecision() == ContractExtractionReviewDecision.EDIT && request.getReviewedValue() == null) {
            throw new BusinessValidationException("EDIT decision requires a reviewedValue");
        }

        fieldResult.setReviewDecision(request.getReviewDecision());
        fieldResult.setReviewedValue(request.getReviewedValue());
        fieldResult.setReviewComment(request.getReviewComment());
        fieldResult.setReviewedByAccountId(accountId);
        fieldResult.setReviewedAt(LocalDateTime.now());

        updateDraftReviewStatus(draft);
        draft.setUpdatedAt(LocalDateTime.now());
        draft = draftRepository.save(draft);
        auditService.log(accountId, AuditAction.PARTNER_CONTRACT_EXTRACTION_FIELD_REVIEWED, "PartnerContractExtractionDraft", extractionId, "Reviewed field: " + fieldKey);
        return draft;
    }

    @Transactional
    public PartnerContractExtractionDraft reviewClauseByTask(Long taskId, String extractionId, String clauseCandidateId, com.apms.domain.contract.dto.ReviewExtractionClauseRequest request, Long accountId) {
        PartnerContractExtractionDraft draft = getDraftForTask(extractionId, taskId);
        validateProjectAccess(draft.getSourceProjectId(), accountId, true);
        ensureDraftIsEditable(draft);

        ClauseCandidate clause = draft.getClauseCandidates().stream()
                .filter(c -> c.getClauseCandidateId().equals(clauseCandidateId))
                .findFirst()
                .orElseThrow(() -> new BusinessValidationException("Clause not found: " + clauseCandidateId));

        clause.setReviewDecision(request.getReviewDecision());
        clause.setReviewComment(request.getReviewComment());

        if (request.getReviewDecision() == ContractExtractionReviewDecision.EDIT) {
            if (!StringUtils.hasText(request.getReviewComment())) {
                throw new BusinessValidationException("EDIT decision requires a reviewComment");
            }
            clause.setClauseTitle(request.getClauseTitle());
            clause.setClauseType(request.getClauseType());
            clause.setNormalizedTerms(request.getNormalizedTerms());
            clause.setEffectiveDate(request.getEffectiveDate());
            clause.setExpiryDate(request.getExpiryDate());
            clause.setNoticePeriodDays(request.getNoticePeriodDays());
            clause.setTargetMetricKey(request.getTargetMetricKey());
            clause.setTargetValue(request.getTargetValue());
            clause.setTargetUnit(request.getTargetUnit());
            clause.setComparator(request.getComparator());
            clause.setMeasurementPeriod(request.getMeasurementPeriod());
            clause.setPenaltyValue(request.getPenaltyValue());
            clause.setPenaltyCurrency(request.getPenaltyCurrency());
            clause.setPenaltyDescription(request.getPenaltyDescription());
            clause.setReviewedFields(request.getReviewedFields());
        }

        clause.setReviewedByAccountId(accountId);
        clause.setReviewedAt(LocalDateTime.now());

        updateDraftReviewStatus(draft);
        draft.setUpdatedAt(LocalDateTime.now());
        draft = draftRepository.save(draft);
        auditService.log(accountId, AuditAction.PARTNER_CONTRACT_EXTRACTION_CLAUSE_REVIEWED, "PartnerContractExtractionDraft", extractionId, "Reviewed clause: " + clauseCandidateId);
        return draft;
    }

    private void updateDraftReviewStatus(PartnerContractExtractionDraft draft) {
        boolean allReviewed = true;
        boolean anyReviewed = false;

        for (ContractExtractionFieldResult f : draft.getFieldResults()) {
            if (f.getReviewDecision() == null) allReviewed = false;
            else anyReviewed = true;
        }
        for (ClauseCandidate c : draft.getClauseCandidates()) {
            if (c.getReviewDecision() == null) allReviewed = false;
            else anyReviewed = true;
        }

        if (allReviewed) {
            draft.setReviewStatus(ContractExtractionReviewStatus.REVIEWED);
        } else if (anyReviewed) {
            draft.setReviewStatus(ContractExtractionReviewStatus.IN_REVIEW);
        }
    }

    private PartnerContractExtractionDraft getDraftForContract(String extractionId, Long contractId) {
        return draftRepository.findByIdAndPartnerContractId(extractionId, contractId)
                .orElseThrow(() -> new BusinessValidationException("Extraction not found or does not belong to contract"));
    }

    private PartnerContractExtractionDraft getDraftForTask(String extractionId, Long taskId) {
        return draftRepository.findByIdAndSourceTaskId(extractionId, taskId)
                .orElseThrow(() -> new BusinessValidationException("Extraction not found or does not belong to task"));
    }

    private void ensureDraftIsEditable(PartnerContractExtractionDraft draft) {
        if (draft.getApplicationStatus() == ContractExtractionApplicationStatus.APPLY_PENDING) {
            recoverOrRejectPendingApply(draft);
        }
        if (draft.getApplicationStatus() == ContractExtractionApplicationStatus.APPLIED_FROZEN) {
            throw new BusinessValidationException("Extraction is APPLIED_FROZEN and cannot be edited.");
        }
        if (draft.getApplicationStatus() != ContractExtractionApplicationStatus.NOT_APPLIED) {
            throw new BusinessValidationException("Extraction is locked during apply process.");
        }
    }

    private void recoverOrRejectPendingApply(PartnerContractExtractionDraft draft) {
        if (draft.getApplicationStatus() != ContractExtractionApplicationStatus.APPLY_PENDING) return;

        PartnerContract contract = partnerContractRepository.findById(draft.getPartnerContractId()).orElse(null);
        if (contract == null) return;

        boolean sqlLinkageExists = draft.getId().equals(contract.getPendingExtractionId());

        if (sqlLinkageExists) {
            // Finalize to APPLIED_FROZEN
            draft.setApplicationStatus(ContractExtractionApplicationStatus.APPLIED_FROZEN);
            draft.setApprovalSyncStatus(ContractExtractionApprovalSyncStatus.PENDING);
            draftRepository.save(draft);
            return;
        }

        // SQL Linkage doesn't exist. Can we reset?
        if (draft.getApplyStartedAt() != null && draft.getApplyStartedAt().plusSeconds(config.getApplyRecoveryTimeoutSeconds()).isBefore(LocalDateTime.now())) {
            if (contract.getVersion().equals(draft.getContractVersionAtGeneration())) {
                draft.setApplicationStatus(ContractExtractionApplicationStatus.NOT_APPLIED);
                draft.setApplyOperationId(null);
                draft.setApplyStartedAt(null);
                draftRepository.save(draft);
            }
        }
    }

    @Transactional
    public PartnerContractExtractionDraft applyExtraction(Long contractId, String extractionId, ApplyExtractionRequest request, Long accountId) {
        PartnerContractExtractionDraft draft = getDraftForContract(extractionId, contractId);
        validateProjectAccess(draft.getSourceProjectId(), accountId, true);

        if (draft.getApplicationStatus() == ContractExtractionApplicationStatus.APPLY_PENDING) {
            recoverOrRejectPendingApply(draft);
        }

        if (draft.getApplicationStatus() == ContractExtractionApplicationStatus.APPLIED_FROZEN) {
            return draft; // Idempotent
        }

        ensureDraftIsEditable(draft);

        if (draft.getReviewStatus() != ContractExtractionReviewStatus.REVIEWED) {
            throw new BusinessValidationException("All fields and clauses must have a terminal decision before apply.");
        }

        PartnerContract contract = partnerContractRepository.findById(contractId)
                .orElseThrow(() -> new BusinessValidationException("PartnerContract not found: " + contractId));

        if (!contract.getVersion().equals(request.getExpectedContractOptimisticVersion())) {
            throw new BusinessValidationException("Stale revision. The contract has been updated since this extraction was viewed.");
        }

        if (contract.getReviewStatus() == ContractReviewStatus.APPROVED ||
            contract.getReviewStatus() == ContractReviewStatus.IN_REVIEW) {
            throw new BusinessValidationException("Cannot apply to a contract that is APPROVED or IN_REVIEW.");
        }

        if (!draft.getContractVersionAtGeneration().equals(contract.getVersion())) {
            throw new BusinessValidationException("Extraction was generated from an outdated contract revision.");
        }

        if (!draft.getSourceDocumentHash().equals(request.getExpectedSourceDocumentHash())) {
            throw new BusinessValidationException("Source document hash mismatch.");
        }

        if (!draft.getCurrentApprovedVersion().equals(request.getExpectedCurrentApprovedVersion()) ||
            !draft.getExpectedNextApprovalVersion().equals(request.getExpectedNextApprovalVersion())) {
            throw new BusinessValidationException("Approval version mismatch.");
        }

        // 1. Mongo: NOT_APPLIED -> APPLY_PENDING
        draft.setApplicationStatus(ContractExtractionApplicationStatus.APPLY_PENDING);
        draft.setApplyOperationId(UUID.randomUUID().toString());
        draft.setApplyStartedAt(LocalDateTime.now());
        draft = draftRepository.save(draft);

        // 2. Apply ACCEPTED/EDITED fields to contract in SQL
        for (ContractExtractionFieldResult field : draft.getFieldResults()) {
            if (field.getReviewDecision() == ContractExtractionReviewDecision.ACCEPT ||
                field.getReviewDecision() == ContractExtractionReviewDecision.EDIT) {

                String key = field.getFieldName();
                Object valueToApply = field.getReviewedValue() != null ? field.getReviewedValue() : field.getNormalizedValue();
                applyFieldToContract(contract, key, valueToApply, request.getConfirmedOverwriteFieldKeys());
            }
        }

        String clauseHashSource = draft.getClauseCandidates().stream()
                .filter(c -> c.getReviewDecision() == ContractExtractionReviewDecision.ACCEPT ||
                             c.getReviewDecision() == ContractExtractionReviewDecision.EDIT)
                .map(c -> c.getClauseCandidateId() + ":" + c.getReviewDecision())
                .reduce("", String::concat);

        String hash = DigestUtils.md5DigestAsHex(clauseHashSource.getBytes());

        contract.setPendingExtractionId(extractionId);
        contract.setPendingClauseSetHash(hash);
        contract.setUpdatedByAccountId(accountId);
        partnerContractRepository.save(contract); // Flushes SQL

        // 3. Mongo: APPLY_PENDING -> APPLIED_FROZEN
        draft.setApplicationStatus(ContractExtractionApplicationStatus.APPLIED_FROZEN);
        draft.setApprovalSyncStatus(ContractExtractionApprovalSyncStatus.PENDING);
        draft.setClauseSetHash(hash);
        draft.setAppliedAt(LocalDateTime.now());
        draft.setAppliedByAccountId(accountId);
        draft = draftRepository.save(draft);

        auditService.log(accountId, AuditAction.PARTNER_CONTRACT_EXTRACTION_APPLIED, "PartnerContractExtractionDraft", extractionId, "Applied extraction to contract");
        return draft;
    }

    private void applyFieldToContract(PartnerContract contract, String key, Object value, List<String> confirmedOverwrites) {
        if (value == null) return;

        Object existingValue = getContractValue(contract, key);
        if (existingValue != null && !existingValue.equals(value)) {
            if (confirmedOverwrites == null || !confirmedOverwrites.contains(key)) {
                throw new BusinessValidationException("Overwrite conflict detected for field: " + key + ". Explicit confirmation required.");
            }
        }

        switch (key) {
            case "contractNumber" -> contract.setContractNumber(value.toString());
            case "contractTitle" -> contract.setContractTitle(value.toString());
            case "contractType" -> contract.setContractType(value.toString());
            case "currency" -> contract.setCurrency(value.toString().toUpperCase());
            case "signedDate" -> contract.setSignedDate(LocalDate.parse(value.toString()));
            case "effectiveDate" -> contract.setEffectiveDate(LocalDate.parse(value.toString()));
            case "expiryDate" -> contract.setExpiryDate(LocalDate.parse(value.toString()));
            case "totalContractValue" -> contract.setTotalContractValue(new java.math.BigDecimal(value.toString()));
        }
    }

    private Object getContractValue(PartnerContract contract, String key) {
        return switch (key) {
            case "contractNumber" -> contract.getContractNumber();
            case "contractTitle" -> contract.getContractTitle();
            case "contractType" -> contract.getContractType();
            case "currency" -> contract.getCurrency();
            case "signedDate" -> contract.getSignedDate();
            case "effectiveDate" -> contract.getEffectiveDate();
            case "expiryDate" -> contract.getExpiryDate();
            case "totalContractValue" -> contract.getTotalContractValue();
            default -> null;
        };
    }

    @Transactional
    public PartnerContractExtractionDraft regenerateExtraction(Long contractId, String extractionId, boolean force, String comment, Long accountId) {
        PartnerContractExtractionDraft draft = getDraftForContract(extractionId, contractId);
        validateProjectAccess(draft.getSourceProjectId(), accountId, true);

        if ((draft.getApplicationStatus() == ContractExtractionApplicationStatus.APPLIED_FROZEN ||
             draft.getReviewStatus() != ContractExtractionReviewStatus.PENDING) && !force) {
            throw new BusinessValidationException("Cannot regenerate a reviewed or applied extraction without force=true.");
        }
        if (force && !StringUtils.hasText(comment)) {
            throw new BusinessValidationException("Force regeneration requires a comment.");
        }

        PartnerContractExtractionDraft newDraft = generateExtraction(contractId, accountId);

        draft.setApplicationStatus(ContractExtractionApplicationStatus.SUPERSEDED);
        draft.setSupersededByExtractionId(newDraft.getId());
        draftRepository.save(draft);

        auditService.log(accountId, AuditAction.PARTNER_CONTRACT_EXTRACTION_REGENERATED, "PartnerContractExtractionDraft", extractionId, "Regenerated to " + newDraft.getId() + ". Reason: " + comment);

        return newDraft;
    }

    private String extractText(RawDocument doc) {
        if (doc.getSource() == null || doc.getSource().getType() == null) return null;
        if ("MANUAL_INPUT".equalsIgnoreCase(doc.getSource().getType())) {
            return doc.getSource().getInputText();
        }
        return doc.getSource().getInputText();
    }
    public String generateSegmentId(String docHash, int startOffset, int endOffset) {
        return DigestUtils.md5DigestAsHex((docHash + ":" + startOffset + ":" + endOffset).getBytes());
    }

    public ContractDocumentSegment validateSegment(PartnerContract contract, String segmentId, int startOffset, int endOffset, String excerpt, String expectedDocHash, String expectedRawDocId, String expectedProjectId) {
        if (contract.getRawDocumentId() == null) {
            throw new BusinessValidationException("Contract does not have a linked RawDocument.");
        }
        if (!contract.getRawDocumentId().equals(expectedRawDocId)) {
            throw new BusinessValidationException("Segment RawDocument ID mismatch.");
        }
        if (!String.valueOf(contract.getSourceProjectId()).equals(expectedProjectId)) {
            throw new BusinessValidationException("Segment Project mismatch.");
        }

        RawDocument doc = rawDocumentRepository.findById(expectedRawDocId)
                .orElseThrow(() -> new BusinessValidationException("RawDocument not found: " + expectedRawDocId));

        String sourceText = extractText(doc);
        if (sourceText == null || sourceText.isEmpty()) {
            throw new BusinessValidationException("Source text is empty.");
        }

        String actualDocHash = doc.getStorage() != null && doc.getStorage().getChecksum() != null ?
                doc.getStorage().getChecksum() : DigestUtils.md5DigestAsHex(sourceText.getBytes());

        if (!actualDocHash.equals(expectedDocHash)) {
            throw new BusinessValidationException("Source document hash mismatch. The document has changed.");
        }

        if (startOffset < 0 || endOffset > sourceText.length() || startOffset >= endOffset) {
            throw new BusinessValidationException("Invalid segment offsets.");
        }

        String actualSegmentId = generateSegmentId(actualDocHash, startOffset, endOffset);
        if (!actualSegmentId.equals(segmentId)) {
            throw new BusinessValidationException("Unknown segment reference.");
        }

        String actualExcerpt = sourceText.substring(startOffset, endOffset);
        if (!actualExcerpt.equals(excerpt)) {
            throw new BusinessValidationException("Segment excerpt mismatch.");
        }

        return ContractDocumentSegment.builder()
                .segmentId(segmentId)
                .rawDocumentId(expectedRawDocId)
                .sourceDocumentHash(actualDocHash)
                .startOffset(startOffset)
                .endOffset(endOffset)
                .excerpt(excerpt)
                .excerptHash(DigestUtils.md5DigestAsHex(excerpt.getBytes()))
                .build();
    }

    @Transactional(readOnly = true)
    public List<PartnerContractExtractionDraft> listExtractions(Long contractId, Long accountId) {
        PartnerContract contract = partnerContractRepository.findById(contractId)
                .orElseThrow(() -> new BusinessValidationException("Contract not found: " + contractId));
        validateProjectAccess(contract.getSourceProjectId(), accountId, false);
        return draftRepository.findByPartnerContractIdOrderByGeneratedAtDesc(contractId);
    }

    @Transactional(readOnly = true)
    public PartnerContractExtractionDraft getExtraction(Long contractId, String extractionId, Long accountId) {
        PartnerContractExtractionDraft draft = getDraftForContract(extractionId, contractId);
        validateProjectAccess(draft.getSourceProjectId(), accountId, false);
        return draft;
    }

    @Transactional(readOnly = true)
    public List<PartnerContractExtractionDraft> listExtractionsByTask(Long taskId, Long accountId) {
        com.apms.domain.project.ProjectTask task = projectTaskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessValidationException("Task not found: " + taskId));
        validateProjectAccess(task.getProject().getId(), accountId, false);
        return draftRepository.findBySourceTaskIdOrderByGeneratedAtDesc(taskId);
    }

    @Transactional(readOnly = true)
    public PartnerContractExtractionDraft getExtractionByTask(Long taskId, String extractionId, Long accountId) {
        PartnerContractExtractionDraft draft = getDraftForTask(extractionId, taskId);
        validateProjectAccess(draft.getSourceProjectId(), accountId, false);
        return draft;
    }
}
