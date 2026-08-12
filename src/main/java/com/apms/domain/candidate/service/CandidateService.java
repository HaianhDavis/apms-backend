package com.apms.domain.candidate.service;

import com.apms.common.enums.CandidateStatus;
import com.apms.common.enums.RelationshipType;
import com.apms.common.event.CandidateApprovedEvent;
import com.apms.common.exception.BusinessValidationException;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.ai.AiExtractionCache;
import com.apms.domain.ai.dto.AiExtractionResult;
import com.apms.domain.ai.dto.ExtractedCompanyData;
import com.apms.domain.ai.dto.ExtractionFieldResult;
import com.apms.domain.ai.service.AiExtractionService;
import com.apms.domain.candidate.CompanyCandidate;
import com.apms.domain.candidate.dto.ApproveCandidateRequest;
import com.apms.domain.candidate.dto.CandidateResponse;
import com.apms.domain.candidate.dto.RejectCandidateRequest;
import com.apms.domain.candidate.dto.UpdateCandidateRequest;
import com.apms.domain.candidate.repository.mongo.CompanyCandidateRepository;
import com.apms.domain.project.Project;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.document.ImportJob;
import com.apms.domain.document.RawDocument;
import com.apms.domain.document.repository.mongo.RawDocumentRepository;
import com.apms.domain.document.repository.sql.ImportJobRepository;
import com.apms.domain.project.fieldapproval.CandidateFieldAccessor;
import com.apms.domain.project.fieldapproval.FieldApprovalGuard;
import com.apms.domain.project.fieldapproval.FieldApprovalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CandidateService {

    private final CompanyCandidateRepository candidateRepository;
    private final ImportJobRepository importJobRepository;
    private final ProjectRepository projectRepository;
    private final AiExtractionService aiExtractionService;
    private final ApplicationEventPublisher eventPublisher;
    private final com.apms.domain.profile.service.OwnerOrganizationService ownerOrganizationService;
    private final FieldApprovalGuard fieldApprovalGuard;
    private final FieldApprovalService fieldApprovalService;
    private final ObjectMapper objectMapper;
    private final com.apms.domain.project.repository.sql.ProjectTaskRepository projectTaskRepository;
    private final RawDocumentRepository rawDocumentRepository;

    // ─────────────────────────────────────────────
    // CREATE (from AI)
    // ─────────────────────────────────────────────

    private static final java.util.Set<String> STAFF_REVIEWABLE_FIELDS = java.util.Set.of(
        "identity.legalName", "identity.tradeName", "identity.taxCode",
        "contact.address", "contact.website", "contact.emails", "contact.phones",
        "business.businessModel", "business.industries", "business.markets", "business.targetCustomers", "business.products",
        "companySize.employeeTier", "companySize.employeeCount", "companySize.revenueTier",
        "insights.strengths", "insights.weaknesses", "insights.opportunities", "insights.threats",
        "financial", "innovation", "market", "risk", "compliance"
    );

    @Transactional
    public CandidateResponse createFromAi(Long importJobId, Long creatorId) {
        ImportJob importJob = importJobRepository.findById(importJobId)
                .orElseThrow(() -> new ResourceNotFoundException("ImportJob not found: " + importJobId));

        // 1. Load extraction from cache (set by /ai/extract), or run if not yet cached
        AiExtractionResult aiResult = aiExtractionService.getOrExtractCompanyData(importJobId);
        AiExtractionCache cache = aiExtractionService.getLatestExtraction(importJobId);

        return buildAndSaveCandidate(String.valueOf(importJob.getProjectId()), String.valueOf(importJobId), importJob.getRawDocumentId(), aiResult.getExtractedData(), cache.getFieldResults(), creatorId);
    }

    @Transactional
    public CandidateResponse createFromExtractionId(String extractionId, Long creatorId) {
        com.apms.domain.ai.AiExtractionCache cache = aiExtractionService.getExtractionById(extractionId);

        ImportJob importJob = importJobRepository.findById(cache.getImportJobId())
                .orElseThrow(() -> new ResourceNotFoundException("ImportJob not found: " + cache.getImportJobId()));

        return buildAndSaveCandidate(String.valueOf(importJob.getProjectId()), String.valueOf(importJob.getId()), importJob.getRawDocumentId(), cache.getExtractedData(), cache.getFieldResults(), creatorId);
    }

    private CandidateResponse buildAndSaveCandidate(String projectId, String importJobId, String rawDocumentId, ExtractedCompanyData extractedData,
                                                    java.util.Map<String, ExtractionFieldResult> sourceFieldResults, Long creatorId) {
        // 2. Map extracted data to Candidate flexible embedded documents
        CompanyCandidate.Identity identity = CompanyCandidate.Identity.builder()
                .legalName(extractedData.getLegalName())
                .tradeName(extractedData.getTradeName())
                .taxCode(extractedData.getTaxCode())
                .build();

        CompanyCandidate.Business business = CompanyCandidate.Business.builder()
                .industries(extractedData.getIndustries())
                .businessModel(extractedData.getBusinessModel())
                .products(extractedData.getProducts() != null ? extractedData.getProducts().stream()
                        .map(p -> CompanyCandidate.Product.builder()
                                .name(p.getName())
                                .category(p.getCategory())
                                .description(p.getDescription())
                                .build())
                        .toList() : null)
                .markets(extractedData.getMarkets())
                .targetCustomers(extractedData.getTargetCustomers())
                .build();

        CompanyCandidate.CompanySize size = CompanyCandidate.CompanySize.builder()
                .employeeTier(extractedData.getEmployeeTier())
                .revenueTier(extractedData.getCompanySize())
                .build();

        CompanyCandidate.Contact contact = CompanyCandidate.Contact.builder()
                .website(extractedData.getWebsite())
                .emails(extractedData.getEmail())
                .phones(extractedData.getPhone())
                .addresses(extractedData.getAddress() != null ? java.util.List.of(CompanyCandidate.Address.builder()
                        .fullAddress(extractedData.getAddress())
                        .build()) : null)
                .build();

        CompanyCandidate.Insights insights = CompanyCandidate.Insights.builder()
                .strengths(extractedData.getStrengths())
                .weaknesses(extractedData.getWeaknesses())
                .opportunities(extractedData.getOpportunities())
                .threats(extractedData.getThreats())
                .build();

        CompanyCandidate.RelationshipSuggestion suggestion = null;
        RelationshipType suggestedRel = null;
        Double confidence = null;
        CompanyCandidate.Metadata metadata = CompanyCandidate.Metadata.builder()
                .createdBy(String.valueOf(creatorId))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        CompanyCandidate.Lifecycle lifecycle = CompanyCandidate.Lifecycle.builder()
                .status(CandidateStatus.DRAFT)
                .build();

        // 3. Create Candidate
        CompanyCandidate candidate = CompanyCandidate.builder()
                .projectId(projectId)
                .importJobId(importJobId)
                .rawDocumentId(rawDocumentId)
                .sourceDocumentIds(resolveSourceDocumentIds(rawDocumentId, null))
                .candidateOrder(1)
                .revisionNumber(1)
                .status(CandidateStatus.DRAFT)
                .suggestedRelationshipType(suggestedRel)
                .relationshipConfidenceScore(confidence)
                .relationshipSuggestion(suggestion)
                .identity(identity)
                .business(business)
                .companySize(size)
                .contact(contact)
                .insights(insights)
                .keyPeople(extractedData.getKeyPeople())
                .financial(extractedData.getFinancial())
                .market(extractedData.getMarket())
                .innovation(extractedData.getInnovation())
                .risk(extractedData.getRisk())
                .compliance(extractedData.getCompliance())
                .lifecycle(lifecycle)
                .metadata(metadata)
                .build();

        candidate.setFieldResults(initializeCandidateFieldResults(candidate, sourceFieldResults));

        candidate = candidateRepository.save(candidate);
        log.info("Candidate created from AI: id={}, projectId={}", candidate.getId(), candidate.getProjectId());

        return toResponse(candidate);
    }

    // ─────────────────────────────────────────────
    // READ
    // ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    public CandidateResponse getCandidate(String candidateId) {
        CompanyCandidate candidate = findCandidateOrThrow(candidateId);
        return toResponse(candidate);
    }

    @Transactional(readOnly = true)
    public Page<CandidateResponse> getProjectCandidates(String projectId, Pageable pageable) {
        return candidateRepository.findByProjectId(projectId, pageable).map(this::toResponse);
    }

    // ─────────────────────────────────────────────
    // WORKFLOW STATE MACHINE & UPDATES
    // ─────────────────────────────────────────────

    @Transactional
    public void deleteDraftCandidate(String candidateId) {
        CompanyCandidate candidate = findCandidateOrThrow(candidateId);
        if (candidate.getStatus() != CandidateStatus.DRAFT && candidate.getStatus() != CandidateStatus.REJECTED) {
            throw new BusinessValidationException("Only DRAFT or REJECTED candidates can be deleted");
        }
        candidateRepository.delete(candidate);
        log.info("Candidate draft deleted: id={}, status={}", candidateId, candidate.getStatus());
    }

    @Transactional
    public CandidateResponse updateCandidate(String candidateId, UpdateCandidateRequest request, Long userId) {
        CompanyCandidate candidate = findCandidateOrThrow(candidateId);

        if (candidate.getStatus() == CandidateStatus.APPROVED || candidate.getStatus() == CandidateStatus.REJECTED) {
            throw new BusinessValidationException("Cannot edit candidate in status: " + candidate.getStatus());
        }

        CompanyCandidate oldDraft;
        try {
            oldDraft = objectMapper.readValue(objectMapper.writeValueAsString(candidate), CompanyCandidate.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to clone candidate for guarding", e);
        }

        if (request.getIdentity() != null) candidate.setIdentity(request.getIdentity());
        if (request.getBusiness() != null) candidate.setBusiness(request.getBusiness());
        if (request.getCompanySize() != null) candidate.setCompanySize(request.getCompanySize());
        if (request.getContact() != null) candidate.setContact(request.getContact());
        if (request.getInsights() != null) candidate.setInsights(request.getInsights());
        if (request.getIdentity() != null) candidate.setIdentity(mergeIdentity(candidate.getIdentity(), request.getIdentity()));
        if (request.getBusiness() != null) candidate.setBusiness(mergeBusiness(candidate.getBusiness(), request.getBusiness()));
        if (request.getCompanySize() != null) candidate.setCompanySize(mergeCompanySize(candidate.getCompanySize(), request.getCompanySize()));
        if (request.getContact() != null) candidate.setContact(mergeContact(candidate.getContact(), request.getContact()));
        if (request.getInsights() != null) candidate.setInsights(mergeInsights(candidate.getInsights(), request.getInsights()));
        if (request.getFinancial() != null) candidate.setFinancial(request.getFinancial());
        if (request.getMarket() != null) candidate.setMarket(request.getMarket());
        if (request.getInnovation() != null) candidate.setInnovation(request.getInnovation());
        if (request.getRisk() != null) candidate.setRisk(request.getRisk());
        if (request.getCompliance() != null) candidate.setCompliance(request.getCompliance());
        if (request.getValidation() != null) candidate.setValidation(request.getValidation());

        if (request.getSuggestedRelationshipType() != null) {
            candidate.setSuggestedRelationshipType(request.getSuggestedRelationshipType());
        }

        fieldApprovalGuard.guardMutation(oldDraft, candidate, CandidateFieldAccessor.getAllDefinitions(),
                candidate.getFieldApprovals(), candidate.getStatus() == CandidateStatus.PENDING_REVIEW, candidate.getRevisionNumber());

        // Track changed fields incrementally so submitCandidate knows what changed
        if (candidate.getChangedFieldPaths() == null) {
            candidate.setChangedFieldPaths(new java.util.ArrayList<>());
        }
        for (com.apms.domain.project.fieldapproval.FieldDefinition<CompanyCandidate> def : CandidateFieldAccessor.getAllDefinitions()) {
            Object oldVal = def.getGetter().apply(oldDraft);
            Object newVal = def.getGetter().apply(candidate);
            String hashOld = com.apms.domain.project.fieldapproval.FieldValueHasher.hashValue(oldVal, def.isCollection() && !def.isOrderedCollection());
            String hashNew = com.apms.domain.project.fieldapproval.FieldValueHasher.hashValue(newVal, def.isCollection() && !def.isOrderedCollection());
            if (!java.util.Objects.equals(hashOld, hashNew)) {
                if (candidate.getFieldResults() != null) {
                    com.apms.domain.ai.dto.ExtractionFieldResult fieldResult = candidate.getFieldResults().get(com.apms.domain.ai.service.FieldKeyCodec.encode(def.getCanonicalPath()));
                    if (fieldResult != null && fieldResult.getManagerReviewStatus() == com.apms.domain.ai.dto.ExtractionReviewStatus.ACCEPTED) {
                        throw new BusinessValidationException("FIELD_LOCKED_BY_MANAGER_APPROVAL");
                    }
                }
                if (!candidate.getChangedFieldPaths().contains(def.getCanonicalPath())) {
                    candidate.getChangedFieldPaths().add(def.getCanonicalPath());
                }
            }
        }

        if (candidate.getMetadata() != null) {
            candidate.getMetadata().setLastModifiedBy(String.valueOf(userId));
            candidate.getMetadata().setUpdatedAt(LocalDateTime.now());
        }

        candidate = candidateRepository.save(candidate);
        log.info("Candidate updated manually: id={}, newRevision={}", candidateId, candidate.getRevisionNumber());

        return toResponse(candidate);
    }

    @Transactional
    public CandidateResponse completeCandidateFieldReview(String candidateId, Long userId) {
        CompanyCandidate candidate = findCandidateOrThrow(candidateId);
        validateManagerFieldResultsAccepted(candidate);
        
        candidate.setQualityStatus(com.apms.domain.ai.dto.ExtractionQualityStatus.REVIEWED);
        
        if (candidate.getMetadata() != null) {
            candidate.getMetadata().setLastModifiedBy(String.valueOf(userId));
            candidate.getMetadata().setUpdatedAt(LocalDateTime.now());
        }
        
        candidate = candidateRepository.save(candidate);
        log.info("Candidate field review completed: id={}, qualityStatus={}", candidateId, candidate.getQualityStatus());
        return toResponse(candidate);
    }

    @Transactional
    public CandidateResponse sendBackCandidate(String candidateId, Long userId) {
        CompanyCandidate candidate = findCandidateOrThrow(candidateId);
        
        boolean alreadyRevisionRequired = candidate.getStatus() == CandidateStatus.REVISION_REQUIRED;
        if (candidate.getRevisionNumber() == null) {
            candidate.setRevisionNumber(1);
        }
        if (!alreadyRevisionRequired) {
            candidate.setRevisionNumber(candidate.getRevisionNumber() + 1);
        }
        candidate.setStatus(CandidateStatus.REVISION_REQUIRED);
        prepareReturnedFieldsForStaffRevision(candidate);
        
        if (candidate.getMetadata() != null) {
            candidate.getMetadata().setUpdatedAt(LocalDateTime.now());
            candidate.getMetadata().setLastModifiedBy(String.valueOf(userId));
        }
        
        candidate = candidateRepository.save(candidate);
        log.info("Candidate sent back for revision: id={}, newRevision={}", candidateId, candidate.getRevisionNumber());
        return toResponse(candidate);
    }

    private CompanyCandidate.Identity mergeIdentity(CompanyCandidate.Identity current, CompanyCandidate.Identity incoming) {
        if (current == null) return incoming;
        return CompanyCandidate.Identity.builder()
                .legalName(incoming.getLegalName())
                .tradeName(incoming.getTradeName())
                .taxCode(incoming.getTaxCode())
                .registrationNumber(incoming.getRegistrationNumber() != null ? incoming.getRegistrationNumber() : current.getRegistrationNumber())
                .build();
    }

    private CompanyCandidate.Business mergeBusiness(CompanyCandidate.Business current, CompanyCandidate.Business incoming) {
        if (current == null) return incoming;
        return CompanyCandidate.Business.builder()
                .industries(incoming.getIndustries())
                .businessModel(incoming.getBusinessModel())
                .products(incoming.getProducts() != null ? incoming.getProducts() : current.getProducts())
                .markets(incoming.getMarkets() != null ? incoming.getMarkets() : current.getMarkets())
                .targetCustomers(incoming.getTargetCustomers() != null ? incoming.getTargetCustomers() : current.getTargetCustomers())
                .build();
    }

    private CompanyCandidate.CompanySize mergeCompanySize(CompanyCandidate.CompanySize current, CompanyCandidate.CompanySize incoming) {
        if (current == null) return incoming;
        return CompanyCandidate.CompanySize.builder()
                .employeeTier(incoming.getEmployeeTier() != null ? incoming.getEmployeeTier() : current.getEmployeeTier())
                .employeeCount(incoming.getEmployeeCount() != null ? incoming.getEmployeeCount() : current.getEmployeeCount())
                .revenueTier(incoming.getRevenueTier() != null ? incoming.getRevenueTier() : current.getRevenueTier())
                .build();
    }

    private CompanyCandidate.Contact mergeContact(CompanyCandidate.Contact current, CompanyCandidate.Contact incoming) {
        if (current == null) return incoming;
        return CompanyCandidate.Contact.builder()
                .website(incoming.getWebsite())
                .emails(incoming.getEmails())
                .phones(incoming.getPhones())
                .addresses(incoming.getAddresses() != null ? incoming.getAddresses() : current.getAddresses())
                .build();
    }

    private CompanyCandidate.Insights mergeInsights(CompanyCandidate.Insights current, CompanyCandidate.Insights incoming) {
        if (current == null) return incoming;
        return CompanyCandidate.Insights.builder()
                .strengths(incoming.getStrengths())
                .weaknesses(incoming.getWeaknesses())
                .opportunities(incoming.getOpportunities())
                .threats(incoming.getThreats())
                .build();
    }

    @Transactional
    public CandidateResponse submitCandidate(String candidateId, Long submitterId) {
        CompanyCandidate candidate = findCandidateOrThrow(candidateId);

        if (candidate.getStatus() == CandidateStatus.PENDING_REVIEW) {
            // Might be a retry of a successful Mongo save that failed in SQL. Allow to return for idempotency check.
            return toResponse(candidate);
        }

        if (candidate.getStatus() != CandidateStatus.DRAFT && candidate.getStatus() != CandidateStatus.CORRECTED && candidate.getStatus() != CandidateStatus.REVISION_REQUIRED) {
            throw new BusinessValidationException("Only DRAFT, CORRECTED, or REVISION_REQUIRED candidates can be submitted");
        }

        // Validate that all populated/extracted fields have been CONFIRMED by staff
        if (candidate.getFieldResults() != null) {
            java.util.List<String> unconfirmedFields = new java.util.ArrayList<>();
            for (com.apms.domain.ai.dto.ExtractionFieldResult result : candidate.getFieldResults().values()) {
                if (!STAFF_REVIEWABLE_FIELDS.contains(result.getFieldName())) {
                    continue;
                }

                boolean hasValue = false;
                if (result.getStaffReviewedValue() != null) {
                    if (result.getStaffReviewedValue() instanceof java.util.List) {
                        hasValue = !((java.util.List<?>) result.getStaffReviewedValue()).isEmpty();
                    } else if (result.getStaffReviewedValue() instanceof String) {
                        hasValue = !((String) result.getStaffReviewedValue()).trim().isEmpty();
                    } else {
                        hasValue = true;
                    }
                } else if (result.getValue() != null) {
                    if (result.getValue() instanceof java.util.List) {
                        hasValue = !((java.util.List<?>) result.getValue()).isEmpty();
                    } else if (result.getValue() instanceof String) {
                        hasValue = !((String) result.getValue()).trim().isEmpty();
                    } else {
                        hasValue = true;
                    }
                }

                boolean isRemoved = result.getStaffReviewStatus() == com.apms.domain.ai.dto.StaffFieldReviewStatus.REMOVED;
                boolean isAdded = result.getStaffReviewStatus() == com.apms.domain.ai.dto.StaffFieldReviewStatus.ADDED;
                boolean isEdited = result.getStaffReviewStatus() == com.apms.domain.ai.dto.StaffFieldReviewStatus.EDITED;
                boolean isManagerAccepted = result.getManagerReviewStatus() == com.apms.domain.ai.dto.ExtractionReviewStatus.ACCEPTED;

                if (!isManagerAccepted && (hasValue || isRemoved || isAdded || isEdited)) {
                    if (result.getStaffReviewStatus() != com.apms.domain.ai.dto.StaffFieldReviewStatus.CONFIRMED) {
                        unconfirmedFields.add(result.getFieldName());
                    }
                }
            }
            if (!unconfirmedFields.isEmpty()) {
                throw new BusinessValidationException("Candidate contains unconfirmed fields: " + String.join(", ", unconfirmedFields));
            }
        }

        boolean isFirstSubmission = (candidate.getFieldApprovals() == null || candidate.getFieldApprovals().isEmpty());

        if (isFirstSubmission) {
            candidate.setRevisionNumber(1);
            candidate.setFieldApprovals(new java.util.ArrayList<>());
            fieldApprovalService.initializeFirstSubmission(candidate, CandidateFieldAccessor.getAllDefinitions(), candidate.getFieldApprovals());
        } else {
            // Resubmission: sendBackCandidate already opened the next revision. Do not increment again.
            if (candidate.getRevisionNumber() == null) {
                candidate.setRevisionNumber(1);
            }
            if (candidate.getChangedFieldPaths() != null) {
                java.util.Map<String, com.apms.domain.project.fieldapproval.FieldApprovalRecord> map = com.apms.domain.project.fieldapproval.FieldApprovalUtils.toMap(candidate.getFieldApprovals());
                for (String path : candidate.getChangedFieldPaths()) {
                    com.apms.domain.project.fieldapproval.FieldApprovalRecord record = map.get(path);
                    if (record == null) {
                        record = com.apms.domain.project.fieldapproval.FieldApprovalRecord.builder().fieldPath(path).build();
                        candidate.getFieldApprovals().add(record);
                        map.put(path, record);
                    }
                    if (record.getStatus() == com.apms.common.enums.FieldApprovalStatus.REVISION_REQUIRED
                            || record.getStatus() == com.apms.common.enums.FieldApprovalStatus.REJECTED) {
                        record.setPreviousStatus(record.getStatus());
                        record.setPreviousComment(record.getComment());
                    }
                    record.setStatus(com.apms.common.enums.FieldApprovalStatus.PENDING_REVIEW);
                    record.setChangedInRevision(candidate.getRevisionNumber());
                    record.setReviewedByAccountId(null);
                    record.setReviewedAt(null);
                }
            }
        }

        candidate.setChangedFieldPaths(new java.util.ArrayList<>()); // reset for next review cycle
        candidate.setLastSubmittedAt(LocalDateTime.now());
        candidate.setLastSubmittedByAccountId(submitterId);

        candidate.setStatus(CandidateStatus.PENDING_REVIEW);
        if (candidate.getMetadata() != null) {
            candidate.getMetadata().setUpdatedAt(LocalDateTime.now());
        }

        candidate = candidateRepository.save(candidate);
        log.info("Candidate submitted for review: id={}", candidateId);
        return toResponse(candidate);
    }

    @Transactional
    public void reviewFields(String candidateId, com.apms.domain.project.dto.FieldReviewRequest request, Long reviewerId) {
        CompanyCandidate candidate = findCandidateOrThrow(candidateId);
        if (!java.util.Objects.equals(candidate.getRevisionNumber(), request.getExpectedRevisionNumber())) {
            throw new BusinessValidationException("Revision mismatch. Expected: " + request.getExpectedRevisionNumber() + ", Actual: " + candidate.getRevisionNumber());
        }
        if (!java.util.Objects.equals(candidate.getDocumentVersion(), request.getExpectedDocumentVersion())) {
            throw new BusinessValidationException("Document version mismatch (optimistic locking). Expected: " + request.getExpectedDocumentVersion() + ", Actual: " + candidate.getDocumentVersion());
        }

        fieldApprovalService.processBatchReview(
                candidate, request, reviewerId,
                "CompanyCandidate", candidateId,
                candidate.getFieldApprovals(),
                com.apms.domain.project.fieldapproval.CandidateFieldAccessor.getAllDefinitions(),
                candidate.getRevisionNumber()
        );

        candidateRepository.save(candidate);
    }

    @Transactional
    public void reopenField(String candidateId, String fieldPath, com.apms.domain.project.dto.FieldReopenRequest request, Long reviewerId) {
        CompanyCandidate candidate = findCandidateOrThrow(candidateId);
        if (!java.util.Objects.equals(candidate.getRevisionNumber(), request.getExpectedRevisionNumber())) {
            throw new BusinessValidationException("Revision mismatch. Expected: " + request.getExpectedRevisionNumber() + ", Actual: " + candidate.getRevisionNumber());
        }
        if (!java.util.Objects.equals(candidate.getDocumentVersion(), request.getExpectedDocumentVersion())) {
            throw new BusinessValidationException("Document version mismatch (optimistic locking). Expected: " + request.getExpectedDocumentVersion() + ", Actual: " + candidate.getDocumentVersion());
        }

        fieldApprovalService.processReopen(
                fieldPath, request, reviewerId,
                "CompanyCandidate", candidateId,
                candidate.getFieldApprovals()
        );

        candidateRepository.save(candidate);
    }

    @Transactional(readOnly = true)
    public void validateFinalReviewReadiness(String candidateId, com.apms.common.enums.ReviewDecision decision) {
        CompanyCandidate candidate = findCandidateOrThrow(candidateId);
        fieldApprovalService.validateFinalReviewReadiness(candidate, candidate.getFieldApprovals(), com.apms.domain.project.fieldapproval.CandidateFieldAccessor.getAllDefinitions(), decision);
        if (decision == com.apms.common.enums.ReviewDecision.APPROVE) {
            validateManagerFieldResultsAccepted(candidate);
        }
    }

    @Transactional(readOnly = true)
    public com.apms.domain.project.dto.ReviewSummaryResponse getReviewSummary(String candidateId, Integer submittedRevisionNumber) {
        CompanyCandidate candidate = findCandidateOrThrow(candidateId);

        boolean readyForApproval = true;
        java.util.List<String> blockingFields = new java.util.ArrayList<>();
        if (candidate.getFieldApprovals() != null) {
            for (com.apms.domain.project.fieldapproval.FieldApprovalRecord record : candidate.getFieldApprovals()) {
                if (record.getStatus() == com.apms.common.enums.FieldApprovalStatus.PENDING_REVIEW || record.getStatus() == com.apms.common.enums.FieldApprovalStatus.REVISION_REQUIRED) {
                    readyForApproval = false;
                    blockingFields.add(record.getFieldPath());
                }
            }
        }

        java.util.Map<String, com.apms.domain.project.dto.FieldApprovalResponse> mappedApprovals = new java.util.HashMap<>();
        if (candidate.getFieldApprovals() != null) {
            candidate.getFieldApprovals().forEach(v -> {
                mappedApprovals.put(v.getFieldPath(), com.apms.domain.project.dto.FieldApprovalResponse.builder()
                        .status(v.getStatus())
                        .reviewedRevision(v.getReviewedRevision())
                        .comment(v.getComment())
                        .previousComment(v.getPreviousComment())
                        .previousStatus(v.getPreviousStatus())
                        .changedInRevision(v.getChangedInRevision())
                        .staleReason(v.getStaleReason())
                        .pendingValue(v.getPendingValue())
                        .pendingEvidenceIds(v.getPendingEvidenceIds())
                        .build());
            });
        }

        return com.apms.domain.project.dto.ReviewSummaryResponse.builder()
                .revisionNumber(candidate.getRevisionNumber())
                .documentVersion(candidate.getDocumentVersion())
                .submittedRevisionNumber(submittedRevisionNumber)
                .fieldApprovals(mappedApprovals)
                .changedFieldPaths(candidate.getChangedFieldPaths())
                .readyForApproval(readyForApproval)
                .blockingFields(blockingFields)
                .build();
    }

    @Transactional
    public CandidateResponse rejectCandidate(String candidateId, RejectCandidateRequest request, Long reviewerId) {
        CompanyCandidate candidate = findCandidateOrThrow(candidateId);

        if (candidate.getStatus() != CandidateStatus.PENDING_REVIEW) {
            throw new BusinessValidationException("Only PENDING_REVIEW candidates can be rejected");
        }

        candidate.setStatus(CandidateStatus.REJECTED);
        candidate.setReview(CompanyCandidate.Review.builder()
                .rejectionReason(request.getRejectionReason())
                .reviewedBy(String.valueOf(reviewerId))
                .reviewedAt(LocalDateTime.now())
                .build());

        if (candidate.getMetadata() != null) {
            candidate.getMetadata().setUpdatedAt(LocalDateTime.now());
        }

        candidate = candidateRepository.save(candidate);
        log.info("Candidate rejected: id={}, reviewerId={}", candidateId, reviewerId);
        return toResponse(candidate);
    }

    @Transactional
    public CandidateResponse correctCandidate(String candidateId) {
        CompanyCandidate candidate = findCandidateOrThrow(candidateId);

        if (candidate.getStatus() != CandidateStatus.REJECTED) {
            throw new BusinessValidationException("Only REJECTED candidates can be corrected");
        }

        candidate.setStatus(CandidateStatus.CORRECTED);
        candidate.setRevisionNumber(candidate.getRevisionNumber() + 1);
        if (candidate.getMetadata() != null) {
            candidate.getMetadata().setUpdatedAt(LocalDateTime.now());
        }

        candidate = candidateRepository.save(candidate);
        log.info("Candidate moved to corrected: id={}, newRevision={}", candidateId, candidate.getRevisionNumber());
        return toResponse(candidate);
    }

    @Transactional
    public CandidateResponse approveCandidate(String candidateId, ApproveCandidateRequest request, Long reviewerId) {
        CompanyCandidate candidate = findCandidateOrThrow(candidateId);

        fieldApprovalService.validateFinalReviewReadiness(
                candidate,
                candidate.getFieldApprovals(),
                com.apms.domain.project.fieldapproval.CandidateFieldAccessor.getAllDefinitions(),
                com.apms.common.enums.ReviewDecision.APPROVE);
        validateManagerFieldResultsAccepted(candidate);

        if (candidate.getStatus() != CandidateStatus.PENDING_REVIEW && candidate.getStatus() != CandidateStatus.APPROVED) {
            throw new BusinessValidationException("Only PENDING_REVIEW candidates can be approved");
        }
        validateCandidateSourceDocumentsAreResearch(candidate);

        // Prevent owner organization from being evaluated as a target
        if (candidate.getDeduplication() != null && ownerOrganizationService.isOwnerCompany(candidate.getDeduplication().getExistingProfileIdMatch())) {
            throw new BusinessValidationException("The Owner Organization cannot be selected as a project target.");
        }

        candidate.setStatus(CandidateStatus.APPROVED);
        candidate.setQualityStatus(com.apms.domain.ai.dto.ExtractionQualityStatus.REVIEWED);
        CompanyCandidate.Lifecycle lifecycle = Optional.ofNullable(candidate.getLifecycle()).orElse(new CompanyCandidate.Lifecycle());
        lifecycle.setStatus(CandidateStatus.APPROVED);
        candidate.setLifecycle(lifecycle);
        if (request.getRelationshipTypeOverride() != null) {
            candidate.setRelationshipTypeOverride(request.getRelationshipTypeOverride());
        }

        CompanyCandidate.Review review = Optional.ofNullable(candidate.getReview()).orElse(new CompanyCandidate.Review());
        review.setReviewedBy(String.valueOf(reviewerId));
        review.setReviewedAt(LocalDateTime.now());
        candidate.setReview(review);

        if (candidate.getMetadata() != null) {
            candidate.getMetadata().setUpdatedAt(LocalDateTime.now());
        }

        candidate = candidateRepository.save(candidate);

        // Determine final relationship type
        final String projectId = candidate.getProjectId();
        Project project = projectRepository.findById(Long.valueOf(projectId))
                .orElseThrow(() -> new BusinessValidationException("Project not found: " + projectId));

        RelationshipType finalType = null;
        if (request.getRelationshipTypeOverride() != null) {
            finalType = request.getRelationshipTypeOverride();
        } else if (candidate.getRelationshipTypeOverride() != null) {
            finalType = candidate.getRelationshipTypeOverride();
        } else {
            finalType = project.getTargetRelationshipType();
        }

        if (finalType == null) {
            throw new BusinessValidationException("targetRelationshipType is required. Project must define relationship type before approving candidate.");
        }

        Double confidence = candidate.getRelationshipConfidenceScore() != null
                ? candidate.getRelationshipConfidenceScore()
                : 1.0;

        // Publish event for Profile & Graph downstream handling
        eventPublisher.publishEvent(new CandidateApprovedEvent(candidateId, candidate.getProjectId(), finalType, confidence));

        CompanyCandidate refreshed = candidateRepository.findById(candidateId).orElse(candidate);
        log.info("Candidate approved: id={}, finalType={}", candidateId, finalType);
        return toResponse(refreshed);
    }

    // ─────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────

    @Transactional
    public CandidateResponse reviewCandidate(String projectId, String candidateId, com.apms.domain.candidate.dto.CandidateReviewRequest request, Long userId) {
        CompanyCandidate candidate = findCandidateOrThrow(candidateId);

        if (!projectId.equals(candidate.getProjectId())) {
            throw new BusinessValidationException("Candidate does not belong to project");
        }

        if (candidate.getFieldResults() == null) {
            candidate.setFieldResults(new java.util.HashMap<>());
        }

        if (request.getFields() != null) {
            for (java.util.Map.Entry<String, com.apms.domain.candidate.dto.CandidateReviewRequest.FieldReviewUpdate> entry : request.getFields().entrySet()) {
                String fieldPath = entry.getKey();
                if (!STAFF_REVIEWABLE_FIELDS.contains(fieldPath)) {
                    throw new BusinessValidationException("Unknown or unsupported candidate review field: " + fieldPath);
                }
                String mapKey = com.apms.domain.ai.service.FieldKeyCodec.encode(fieldPath);
                com.apms.domain.candidate.dto.CandidateReviewRequest.FieldReviewUpdate update = entry.getValue();

                com.apms.domain.ai.dto.ExtractionFieldResult fieldResult = candidate.getFieldResults().get(mapKey);
                if (fieldResult == null) {
                    fieldResult = new com.apms.domain.ai.dto.ExtractionFieldResult();
                    fieldResult.setFieldName(fieldPath);
                    fieldResult.setValue(readEmbeddedField(candidate, fieldPath));
                    candidate.getFieldResults().put(mapKey, fieldResult);
                }

                if (update.isManager()) {
                    if (update.getManagerReviewStatus() != null) {
                        fieldResult.setManagerReviewStatus(update.getManagerReviewStatus());
                    }
                    if (update.isReviewedValuePresent() || update.getManagerReviewStatus() == com.apms.domain.ai.dto.ExtractionReviewStatus.EDITED) {
                        if (update.getManagerReviewStatus() == com.apms.domain.ai.dto.ExtractionReviewStatus.EDITED && !update.isReviewedValuePresent()) {
                            throw new BusinessValidationException("EDITED review status requires a reviewedValue.");
                        }
                        fieldResult.setStaffReviewedValue(update.getReviewedValue());
                    }
                    if (update.getManagerReviewComment() != null) {
                        fieldResult.setManagerReviewComment(update.getManagerReviewComment());
                    }
                    fieldResult.setManagerReviewedByUserId(userId);
                    fieldResult.setManagerReviewedAt(LocalDateTime.now());
                } else {
                    com.apms.domain.project.fieldapproval.FieldApprovalRecord approval = findFieldApproval(candidate, fieldPath);
                    if ((approval != null && approval.getStatus() == com.apms.common.enums.FieldApprovalStatus.APPROVED)
                            || fieldResult.getManagerReviewStatus() == com.apms.domain.ai.dto.ExtractionReviewStatus.ACCEPTED) {
                        throw new BusinessValidationException("This field has already been approved by the Manager and cannot be modified.");
                    }
                    if (update.getStaffReviewStatus() != null) {
                        fieldResult.setStaffReviewStatus(update.getStaffReviewStatus());
                    }
                    if (update.isReviewedValuePresent() || update.getStaffReviewStatus() == com.apms.domain.ai.dto.StaffFieldReviewStatus.EDITED) {
                        if (update.getStaffReviewStatus() == com.apms.domain.ai.dto.StaffFieldReviewStatus.EDITED && !update.isReviewedValuePresent()) {
                            throw new BusinessValidationException("EDITED review status requires a reviewedValue.");
                        }
                        fieldResult.setStaffReviewedValue(update.getReviewedValue());
                    }
                    
                    boolean hasStaffEdited = update.getStaffReviewStatus() == com.apms.domain.ai.dto.StaffFieldReviewStatus.EDITED
                            || update.getStaffReviewStatus() == com.apms.domain.ai.dto.StaffFieldReviewStatus.ADDED
                            || update.getStaffReviewStatus() == com.apms.domain.ai.dto.StaffFieldReviewStatus.REMOVED
                            || update.getStaffReviewStatus() == com.apms.domain.ai.dto.StaffFieldReviewStatus.CONFIRMED
                            || update.isReviewedValuePresent();
                    if (candidate.getStatus() == CandidateStatus.REVISION_REQUIRED
                            && hasStaffEdited
                            && isReturnedFieldForRevision(approval, fieldResult)) {
                        markReturnedFieldReadyForNextManagerReview(candidate, fieldPath, fieldResult, approval);
                    }

                    if (update.getStaffReviewComment() != null) {
                        fieldResult.setStaffReviewComment(update.getStaffReviewComment());
                    }
                    fieldResult.setStaffReviewedByUserId(userId);
                    fieldResult.setStaffReviewedAt(LocalDateTime.now());
                }

                Object val;
                if (update.isReviewedValuePresent()) {
                    val = update.getReviewedValue();
                } else if (fieldResult.getStaffReviewedValue() != null) {
                    val = fieldResult.getStaffReviewedValue();
                } else {
                    val = fieldResult.getValue();
                }

                // Sync to embedded models
                syncEmbeddedField(candidate, fieldPath, val);
                if (update.isManager()) {
                    syncFieldApprovalFromManagerReview(candidate, fieldPath, update.getManagerReviewStatus(), update.getManagerReviewComment(), userId);
                } else if (candidate.getChangedFieldPaths() == null || !candidate.getChangedFieldPaths().contains(fieldPath)) {
                    if (candidate.getChangedFieldPaths() == null) {
                        candidate.setChangedFieldPaths(new java.util.ArrayList<>());
                    }
                    candidate.getChangedFieldPaths().add(fieldPath);
                }
            }
        }

        if (candidate.getMetadata() != null) {
            candidate.getMetadata().setLastModifiedBy(String.valueOf(userId));
            candidate.getMetadata().setUpdatedAt(LocalDateTime.now());
        }

        candidate = candidateRepository.save(candidate);
        return toResponse(candidate);
    }

    private void prepareReturnedFieldsForStaffRevision(CompanyCandidate candidate) {
        if (candidate.getFieldApprovals() == null || candidate.getFieldApprovals().isEmpty()) {
            return;
        }
        if (candidate.getFieldResults() == null) {
            candidate.setFieldResults(new java.util.HashMap<>());
        }

        for (com.apms.domain.project.fieldapproval.FieldApprovalRecord record : candidate.getFieldApprovals()) {
            if (record == null || !isReturnedApprovalStatus(record.getStatus())) {
                continue;
            }
            String fieldPath = record.getFieldPath();
            if (!STAFF_REVIEWABLE_FIELDS.contains(fieldPath)) {
                continue;
            }
            String mapKey = com.apms.domain.ai.service.FieldKeyCodec.encode(fieldPath);
            com.apms.domain.ai.dto.ExtractionFieldResult fieldResult = candidate.getFieldResults().get(mapKey);
            if (fieldResult == null) {
                fieldResult = new com.apms.domain.ai.dto.ExtractionFieldResult();
                fieldResult.setFieldName(fieldPath);
                fieldResult.setValue(readEmbeddedField(candidate, fieldPath));
                candidate.getFieldResults().put(mapKey, fieldResult);
            }

            if (record.getPendingValue() == null) {
                record.setPendingValue(resolveCurrentFieldValue(fieldResult, candidate, fieldPath));
            }
            record.setPreviousStatus(record.getStatus());
            record.setPreviousComment(record.getComment());

            fieldResult.setPreviousManagerReviewStatus(toExtractionReviewStatus(record.getStatus()));
            fieldResult.setPreviousManagerReviewComment(record.getComment());
            fieldResult.setPreviousSubmittedValue(record.getPendingValue());
            fieldResult.setPreviousReviewedRevision(record.getReviewedRevision());
            fieldResult.setChangedInRevision(candidate.getRevisionNumber());
            fieldResult.setManagerReviewStatus(toExtractionReviewStatus(record.getStatus()));
            fieldResult.setManagerReviewComment(record.getComment());
            fieldResult.setManagerReviewedByUserId(record.getReviewedByAccountId());
            fieldResult.setManagerReviewedAt(record.getReviewedAt());
            fieldResult.setStaffReviewStatus(com.apms.domain.ai.dto.StaffFieldReviewStatus.PENDING);
        }
    }

    private com.apms.domain.project.fieldapproval.FieldApprovalRecord findFieldApproval(CompanyCandidate candidate, String fieldPath) {
        if (candidate.getFieldApprovals() == null) {
            return null;
        }
        return com.apms.domain.project.fieldapproval.FieldApprovalUtils
                .toMap(candidate.getFieldApprovals())
                .get(fieldPath);
    }

    private boolean isReturnedFieldForRevision(
            com.apms.domain.project.fieldapproval.FieldApprovalRecord approval,
            com.apms.domain.ai.dto.ExtractionFieldResult fieldResult) {
        return (approval != null && isReturnedApprovalStatus(approval.getStatus()))
                || fieldResult.getManagerReviewStatus() == com.apms.domain.ai.dto.ExtractionReviewStatus.REJECTED
                || fieldResult.getManagerReviewStatus() == com.apms.domain.ai.dto.ExtractionReviewStatus.NEEDS_REVIEW;
    }

    private boolean isReturnedApprovalStatus(com.apms.common.enums.FieldApprovalStatus status) {
        return status == com.apms.common.enums.FieldApprovalStatus.REJECTED
                || status == com.apms.common.enums.FieldApprovalStatus.REVISION_REQUIRED;
    }

    private void markReturnedFieldReadyForNextManagerReview(
            CompanyCandidate candidate,
            String fieldPath,
            com.apms.domain.ai.dto.ExtractionFieldResult fieldResult,
            com.apms.domain.project.fieldapproval.FieldApprovalRecord approval) {
        if (candidate.getFieldApprovals() == null) {
            candidate.setFieldApprovals(new java.util.ArrayList<>());
        }
        if (approval == null) {
            approval = com.apms.domain.project.fieldapproval.FieldApprovalRecord.builder()
                    .fieldPath(fieldPath)
                    .build();
            candidate.getFieldApprovals().add(approval);
        }

        com.apms.domain.ai.dto.ExtractionReviewStatus previousStatus = fieldResult.getPreviousManagerReviewStatus();
        String previousComment = fieldResult.getPreviousManagerReviewComment();
        Object previousSubmittedValue = fieldResult.getPreviousSubmittedValue();
        Integer previousReviewedRevision = fieldResult.getPreviousReviewedRevision();

        if (isReturnedApprovalStatus(approval.getStatus())) {
            previousStatus = toExtractionReviewStatus(approval.getStatus());
            previousComment = approval.getComment();
            previousSubmittedValue = approval.getPendingValue() != null
                    ? approval.getPendingValue()
                    : resolveCurrentFieldValue(fieldResult, candidate, fieldPath);
            previousReviewedRevision = approval.getReviewedRevision();
            approval.setPreviousStatus(approval.getStatus());
            approval.setPreviousComment(approval.getComment());
        } else if (fieldResult.getManagerReviewStatus() == com.apms.domain.ai.dto.ExtractionReviewStatus.REJECTED
                || fieldResult.getManagerReviewStatus() == com.apms.domain.ai.dto.ExtractionReviewStatus.NEEDS_REVIEW) {
            previousStatus = fieldResult.getManagerReviewStatus();
            previousComment = fieldResult.getManagerReviewComment();
            previousSubmittedValue = resolveCurrentFieldValue(fieldResult, candidate, fieldPath);
        }

        approval.setStatus(com.apms.common.enums.FieldApprovalStatus.PENDING_REVIEW);
        approval.setChangedInRevision(candidate.getRevisionNumber());
        approval.setReviewedByAccountId(null);
        approval.setReviewedAt(null);
        approval.setComment(null);

        fieldResult.setPreviousManagerReviewStatus(previousStatus);
        fieldResult.setPreviousManagerReviewComment(previousComment);
        fieldResult.setPreviousSubmittedValue(previousSubmittedValue);
        fieldResult.setPreviousReviewedRevision(previousReviewedRevision);
        fieldResult.setChangedInRevision(candidate.getRevisionNumber());
        fieldResult.setManagerReviewStatus(com.apms.domain.ai.dto.ExtractionReviewStatus.PENDING);
        fieldResult.setManagerReviewComment(null);
        fieldResult.setManagerReviewedByUserId(null);
        fieldResult.setManagerReviewedAt(null);
    }

    private Object resolveCurrentFieldValue(
            com.apms.domain.ai.dto.ExtractionFieldResult fieldResult,
            CompanyCandidate candidate,
            String fieldPath) {
        if (fieldResult.getStaffReviewedValue() != null) {
            return fieldResult.getStaffReviewedValue();
        }
        if (fieldResult.getValue() != null) {
            return fieldResult.getValue();
        }
        return readEmbeddedField(candidate, fieldPath);
    }

    private com.apms.domain.ai.dto.ExtractionReviewStatus toExtractionReviewStatus(com.apms.common.enums.FieldApprovalStatus status) {
        if (status == null) {
            return com.apms.domain.ai.dto.ExtractionReviewStatus.PENDING;
        }
        return switch (status) {
            case APPROVED -> com.apms.domain.ai.dto.ExtractionReviewStatus.ACCEPTED;
            case REJECTED -> com.apms.domain.ai.dto.ExtractionReviewStatus.REJECTED;
            case REVISION_REQUIRED -> com.apms.domain.ai.dto.ExtractionReviewStatus.NEEDS_REVIEW;
            case PENDING_REVIEW, STALE -> com.apms.domain.ai.dto.ExtractionReviewStatus.PENDING;
        };
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, ExtractionFieldResult> initializeCandidateFieldResults(
            CompanyCandidate candidate,
            java.util.Map<String, ExtractionFieldResult> sourceFieldResults) {
        java.util.Map<String, ExtractionFieldResult> initialized = new java.util.HashMap<>();

        if (sourceFieldResults != null) {
            sourceFieldResults.forEach((rawKey, source) -> {
                if (source == null) return;
                String fieldPath = com.apms.domain.ai.service.CandidateFieldRegistry.toPath(
                        source.getFieldName() != null ? source.getFieldName() : rawKey);
                if (!STAFF_REVIEWABLE_FIELDS.contains(fieldPath)) return;

                ExtractionFieldResult copy = objectMapper.convertValue(source, ExtractionFieldResult.class);
                copy.setFieldName(fieldPath);
                if (copy.getValue() == null) {
                    copy.setValue(readEmbeddedField(candidate, fieldPath));
                }
                copy.setStaffReviewedValue(null);
                copy.setStaffReviewStatus(com.apms.domain.ai.dto.StaffFieldReviewStatus.PENDING);
                copy.setManagerReviewStatus(com.apms.domain.ai.dto.ExtractionReviewStatus.PENDING);
                initialized.put(com.apms.domain.ai.service.FieldKeyCodec.encode(fieldPath), copy);
            });
        }

        for (String fieldPath : STAFF_REVIEWABLE_FIELDS) {
            String mapKey = com.apms.domain.ai.service.FieldKeyCodec.encode(fieldPath);
            initialized.computeIfAbsent(mapKey, ignored -> ExtractionFieldResult.builder()
                    .fieldName(fieldPath)
                    .value(readEmbeddedField(candidate, fieldPath))
                    .staffReviewStatus(com.apms.domain.ai.dto.StaffFieldReviewStatus.PENDING)
                    .managerReviewStatus(com.apms.domain.ai.dto.ExtractionReviewStatus.PENDING)
                    .build());
        }

        return initialized;
    }

    private Object readEmbeddedField(CompanyCandidate candidate, String fieldName) {
        if (candidate == null || fieldName == null) return null;
        return switch (fieldName) {
            case "identity.legalName" -> candidate.getIdentity() != null ? candidate.getIdentity().getLegalName() : null;
            case "identity.tradeName" -> candidate.getIdentity() != null ? candidate.getIdentity().getTradeName() : null;
            case "identity.taxCode" -> candidate.getIdentity() != null ? candidate.getIdentity().getTaxCode() : null;
            case "contact.address" -> {
                if (candidate.getContact() == null || candidate.getContact().getAddresses() == null || candidate.getContact().getAddresses().isEmpty()) {
                    yield null;
                }
                yield candidate.getContact().getAddresses().get(0).getFullAddress();
            }
            case "contact.website" -> candidate.getContact() != null ? candidate.getContact().getWebsite() : null;
            case "contact.emails" -> candidate.getContact() != null ? candidate.getContact().getEmails() : null;
            case "contact.phones" -> candidate.getContact() != null ? candidate.getContact().getPhones() : null;
            case "business.businessModel" -> candidate.getBusiness() != null ? candidate.getBusiness().getBusinessModel() : null;
            case "business.industries" -> candidate.getBusiness() != null ? candidate.getBusiness().getIndustries() : null;
            case "business.markets" -> candidate.getBusiness() != null ? candidate.getBusiness().getMarkets() : null;
            case "business.targetCustomers" -> candidate.getBusiness() != null ? candidate.getBusiness().getTargetCustomers() : null;
            case "business.products" -> candidate.getBusiness() != null ? candidate.getBusiness().getProducts() : null;
            case "companySize.employeeTier" -> candidate.getCompanySize() != null ? candidate.getCompanySize().getEmployeeTier() : null;
            case "companySize.employeeCount" -> candidate.getCompanySize() != null ? candidate.getCompanySize().getEmployeeCount() : null;
            case "companySize.revenueTier" -> candidate.getCompanySize() != null ? candidate.getCompanySize().getRevenueTier() : null;
            case "insights.strengths" -> candidate.getInsights() != null ? candidate.getInsights().getStrengths() : null;
            case "insights.weaknesses" -> candidate.getInsights() != null ? candidate.getInsights().getWeaknesses() : null;
            case "insights.opportunities" -> candidate.getInsights() != null ? candidate.getInsights().getOpportunities() : null;
            case "insights.threats" -> candidate.getInsights() != null ? candidate.getInsights().getThreats() : null;
            case "financial" -> candidate.getFinancial();
            case "innovation" -> candidate.getInnovation();
            case "market" -> candidate.getMarket();
            case "risk" -> candidate.getRisk();
            case "compliance" -> candidate.getCompliance();
            default -> null;
        };
    }

    private void syncEmbeddedField(CompanyCandidate candidate, String fieldName, Object value) {
        if (candidate.getIdentity() == null) candidate.setIdentity(new CompanyCandidate.Identity());
        if (candidate.getBusiness() == null) candidate.setBusiness(new CompanyCandidate.Business());
        if (candidate.getContact() == null) candidate.setContact(new CompanyCandidate.Contact());
        if (candidate.getCompanySize() == null) candidate.setCompanySize(new CompanyCandidate.CompanySize());
        if (candidate.getInsights() == null) candidate.setInsights(new CompanyCandidate.Insights());

        try {
            switch (fieldName) {
                case "identity.legalName":
                    candidate.getIdentity().setLegalName((String) value);
                    break;
                case "identity.tradeName":
                    candidate.getIdentity().setTradeName((String) value);
                    break;
                case "identity.taxCode":
                    candidate.getIdentity().setTaxCode((String) value);
                    break;
                case "identity.registrationNumber":
                    candidate.getIdentity().setRegistrationNumber((String) value);
                    break;
                case "business.industries":
                    candidate.getBusiness().setIndustries((List<String>) value);
                    break;
                case "business.businessModel":
                    candidate.getBusiness().setBusinessModel((String) value);
                    break;
                case "business.markets":
                    candidate.getBusiness().setMarkets((List<String>) value);
                    break;
                case "business.targetCustomers":
                    candidate.getBusiness().setTargetCustomers((List<String>) value);
                    break;
                case "business.products":
                    if (value == null) {
                        candidate.getBusiness().setProducts(null);
                    } else {
                        java.util.List<CompanyCandidate.Product> products = objectMapper.convertValue(
                                value,
                                objectMapper.getTypeFactory().constructCollectionType(java.util.List.class, CompanyCandidate.Product.class)
                        );
                        candidate.getBusiness().setProducts(products);
                    }
                    break;
                case "contact.address":
                    candidate.getContact().setAddresses(value != null && !String.valueOf(value).isBlank()
                            ? java.util.List.of(CompanyCandidate.Address.builder().fullAddress(String.valueOf(value)).build())
                            : null);
                    break;
                case "contact.website":
                    candidate.getContact().setWebsite((String) value);
                    break;
                case "contact.emails":
                    candidate.getContact().setEmails((List<String>) value);
                    break;
                case "contact.phones":
                    candidate.getContact().setPhones((List<String>) value);
                    break;
                case "companySize.employeeTier":
                    candidate.getCompanySize().setEmployeeTier((String) value);
                    break;
                case "companySize.employeeCount":
                    candidate.getCompanySize().setEmployeeCount(value instanceof Number ? ((Number) value).intValue() : null);
                    break;
                case "companySize.revenueTier":
                    candidate.getCompanySize().setRevenueTier((String) value);
                    break;
                case "insights.strengths":
                    candidate.getInsights().setStrengths((List<String>) value);
                    break;
                case "insights.weaknesses":
                    candidate.getInsights().setWeaknesses((List<String>) value);
                    break;
                case "insights.opportunities":
                    candidate.getInsights().setOpportunities((List<String>) value);
                    break;
                case "insights.threats":
                    candidate.getInsights().setThreats((List<String>) value);
                    break;
                case "financial":
                    candidate.setFinancial(objectMapper.convertValue(value, com.apms.domain.company.model.FinancialInfo.class));
                    break;
                case "innovation":
                    candidate.setInnovation(objectMapper.convertValue(value, com.apms.domain.company.model.InnovationInfo.class));
                    break;
                case "market":
                    candidate.setMarket(objectMapper.convertValue(value, com.apms.domain.company.model.MarketInfo.class));
                    break;
                case "risk":
                    candidate.setRisk(objectMapper.convertValue(value, com.apms.domain.company.model.RiskInfo.class));
                    break;
                case "compliance":
                    candidate.setCompliance(objectMapper.convertValue(value, com.apms.domain.company.model.ComplianceInfo.class));
                    break;
                default:
                    log.warn("Unmapped field name for synchronization: {}", fieldName);
            }
        } catch (ClassCastException e) {
            log.error("Invalid value type for field {}", fieldName, e);
        }
    }

    public CompanyCandidate findCandidateOrThrow(String candidateId) {
        return candidateRepository.findById(candidateId)
                .orElseThrow(() -> new ResourceNotFoundException("Candidate not found: " + candidateId));
    }

    private void syncFieldApprovalFromManagerReview(
            CompanyCandidate candidate,
            String fieldPath,
            com.apms.domain.ai.dto.ExtractionReviewStatus managerStatus,
            String comment,
            Long reviewerId
    ) {
        if (managerStatus == null) {
            return;
        }

        com.apms.domain.project.fieldapproval.FieldDefinition<CompanyCandidate> definition =
                CandidateFieldAccessor.getDefinition(fieldPath);
        if (definition == null) {
            throw new BusinessValidationException("Unknown or unsupported candidate approval field: " + fieldPath);
        }

        if (candidate.getFieldApprovals() == null) {
            candidate.setFieldApprovals(new java.util.ArrayList<>());
        }

        java.util.Map<String, com.apms.domain.project.fieldapproval.FieldApprovalRecord> approvals =
                com.apms.domain.project.fieldapproval.FieldApprovalUtils.toMap(candidate.getFieldApprovals());
        com.apms.domain.project.fieldapproval.FieldApprovalRecord record = approvals.get(fieldPath);
        if (record == null) {
            record = com.apms.domain.project.fieldapproval.FieldApprovalRecord.builder()
                    .fieldPath(fieldPath)
                    .changedInRevision(candidate.getRevisionNumber())
                    .build();
            candidate.getFieldApprovals().add(record);
        }

        com.apms.common.enums.FieldApprovalStatus approvalStatus = switch (managerStatus) {
            case ACCEPTED -> com.apms.common.enums.FieldApprovalStatus.APPROVED;
            case REJECTED -> com.apms.common.enums.FieldApprovalStatus.REJECTED;
            case NEEDS_REVIEW -> com.apms.common.enums.FieldApprovalStatus.REVISION_REQUIRED;
            case PENDING -> com.apms.common.enums.FieldApprovalStatus.PENDING_REVIEW;
            case EDITED -> throw new BusinessValidationException("EDITED is not a valid manager approval decision for field: " + fieldPath);
        };

        record.setStatus(approvalStatus);
        record.setComment(comment);
        record.setReviewedRevision(candidate.getRevisionNumber());
        record.setReviewedByAccountId(reviewerId);
        record.setReviewedAt(LocalDateTime.now());
        record.setPendingValue(definition.getGetter().apply(candidate));
        if (approvalStatus == com.apms.common.enums.FieldApprovalStatus.APPROVED) {
            Object approvedValue = definition.getGetter().apply(candidate);
            record.setApprovedValueHash(com.apms.domain.project.fieldapproval.FieldValueHasher.hashValue(
                    approvedValue,
                    definition.isCollection() && !definition.isOrderedCollection()
            ));
        }
    }

    private CandidateResponse toResponse(CompanyCandidate c) {
        Double confidenceScore = resolveCandidateConfidence(c);

        java.util.Map<String, com.apms.domain.ai.dto.ExtractionFieldResult> decodedFieldResults = null;
        if (c.getFieldResults() != null) {
            decodedFieldResults = new java.util.HashMap<>();
            for (com.apms.domain.ai.dto.ExtractionFieldResult fr : c.getFieldResults().values()) {
                if (fr.getFieldName() != null) {
                    decodedFieldResults.put(fr.getFieldName(), fr);
                }
            }
        }
        decodedFieldResults = applyFieldApprovalsToDecodedResults(c, decodedFieldResults);

        return CandidateResponse.builder()
                .id(c.getId())
                .projectId(c.getProjectId())
                .importJobId(c.getImportJobId())
                .rawDocumentId(c.getRawDocumentId())
                .sourceDocumentIds(resolveSourceDocumentIds(c.getRawDocumentId(), c.getSourceDocumentIds()))
                .candidateOrder(c.getCandidateOrder())
                .revisionNumber(c.getRevisionNumber())
                .status(c.getStatus())
                .suggestedRelationshipType(c.getSuggestedRelationshipType())
                .relationshipConfidenceScore(c.getRelationshipConfidenceScore())
                .relationshipTypeOverride(c.getRelationshipTypeOverride())
                .relationshipSuggestion(c.getRelationshipSuggestion())
                .lifecycle(c.getLifecycle())
                .identity(c.getIdentity())
                .business(c.getBusiness())
                .companySize(c.getCompanySize())
                .contact(c.getContact())
                .insights(c.getInsights())
                .financial(c.getFinancial())
                .market(c.getMarket())
                .innovation(c.getInnovation())
                .risk(c.getRisk())
                .compliance(c.getCompliance())
                .validation(c.getValidation())
                .normalization(c.getNormalization())
                .deduplication(c.getDeduplication())
                .extractionSource(c.getExtractionSource())
                .review(c.getReview())
                .fieldEvidence(c.getFieldEvidence())
                .fieldResults(decodedFieldResults)
                .fieldApprovals(c.getFieldApprovals())
                .qualityStatus(c.getQualityStatus())
                .qualityMetrics(c.getQualityMetrics())
                .rawAiOutput(c.getRawAiOutput())
                .scorePreview(c.getScorePreview())
                .aiMetadata(c.getAiMetadata())
                .metadata(c.getMetadata())
                .build();
    }

    private java.util.Map<String, com.apms.domain.ai.dto.ExtractionFieldResult> applyFieldApprovalsToDecodedResults(
            CompanyCandidate candidate,
            java.util.Map<String, com.apms.domain.ai.dto.ExtractionFieldResult> decodedFieldResults) {
        if (candidate.getFieldApprovals() == null || candidate.getFieldApprovals().isEmpty()) {
            return decodedFieldResults;
        }
        if (decodedFieldResults == null) {
            decodedFieldResults = new java.util.HashMap<>();
        }

        for (com.apms.domain.project.fieldapproval.FieldApprovalRecord record : candidate.getFieldApprovals()) {
            if (record == null || record.getFieldPath() == null || !STAFF_REVIEWABLE_FIELDS.contains(record.getFieldPath())) {
                continue;
            }

            com.apms.domain.ai.dto.ExtractionFieldResult fieldResult = decodedFieldResults.computeIfAbsent(record.getFieldPath(), fieldPath -> {
                com.apms.domain.ai.dto.ExtractionFieldResult created = new com.apms.domain.ai.dto.ExtractionFieldResult();
                created.setFieldName(fieldPath);
                created.setValue(readEmbeddedField(candidate, fieldPath));
                return created;
            });

            fieldResult.setManagerReviewStatus(toExtractionReviewStatus(record.getStatus()));
            fieldResult.setManagerReviewComment(record.getStatus() == com.apms.common.enums.FieldApprovalStatus.PENDING_REVIEW
                    ? null
                    : record.getComment());
            fieldResult.setManagerReviewedByUserId(record.getReviewedByAccountId());
            fieldResult.setManagerReviewedAt(record.getReviewedAt());
            fieldResult.setChangedInRevision(record.getChangedInRevision());

            if (record.getPreviousStatus() != null) {
                fieldResult.setPreviousManagerReviewStatus(toExtractionReviewStatus(record.getPreviousStatus()));
                fieldResult.setPreviousManagerReviewComment(record.getPreviousComment());
                fieldResult.setPreviousReviewedRevision(record.getReviewedRevision());
                fieldResult.setPreviousSubmittedValue(record.getPendingValue());
            } else if (isReturnedApprovalStatus(record.getStatus())) {
                fieldResult.setPreviousManagerReviewStatus(toExtractionReviewStatus(record.getStatus()));
                fieldResult.setPreviousManagerReviewComment(record.getComment());
                fieldResult.setPreviousReviewedRevision(record.getReviewedRevision());
                fieldResult.setPreviousSubmittedValue(record.getPendingValue());
            }
        }

        return decodedFieldResults;
    }

    private java.util.List<String> resolveSourceDocumentIds(String rawDocumentId, java.util.List<String> sourceDocumentIds) {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        if (sourceDocumentIds != null) {
            sourceDocumentIds.stream()
                    .filter(org.springframework.util.StringUtils::hasText)
                    .map(String::trim)
                    .forEach(ids::add);
        }
        if (org.springframework.util.StringUtils.hasText(rawDocumentId)) {
            ids.add(rawDocumentId.trim());
        }
        return new java.util.ArrayList<>(ids);
    }

    private void validateCandidateSourceDocumentsAreResearch(CompanyCandidate candidate) {
        for (String rawDocumentId : resolveSourceDocumentIds(candidate.getRawDocumentId(), candidate.getSourceDocumentIds())) {
            rawDocumentRepository.findById(rawDocumentId).ifPresent(rawDocument -> {
                if (isPartnerContractRawDocument(rawDocument)) {
                    throw new BusinessValidationException("Partner contract documents cannot be used as Candidate source evidence: " + rawDocumentId);
                }
            });
        }
    }

    private boolean isPartnerContractRawDocument(RawDocument rawDocument) {
        return rawDocument != null
                && rawDocument.getSource() != null
                && "PARTNER_CONTRACT".equalsIgnoreCase(rawDocument.getSource().getType());
    }

    private void validateManagerFieldResultsAccepted(CompanyCandidate candidate) {
        if (candidate.getFieldResults() == null) {
            return;
        }

        java.util.Map<String, com.apms.domain.project.fieldapproval.FieldApprovalRecord> approvals =
                com.apms.domain.project.fieldapproval.FieldApprovalUtils.toMap(candidate.getFieldApprovals());

        for (com.apms.domain.ai.dto.ExtractionFieldResult result : candidate.getFieldResults().values()) {
            if (result == null || !STAFF_REVIEWABLE_FIELDS.contains(result.getFieldName())) {
                continue;
            }

            com.apms.domain.project.fieldapproval.FieldApprovalRecord approval = approvals.get(result.getFieldName());
            if (approval != null) {
                if (approval.getStatus() == com.apms.common.enums.FieldApprovalStatus.APPROVED) {
                    continue;
                }
                throw new BusinessValidationException("ALL_FIELDS_MUST_BE_ACCEPTED");
            }

            com.apms.domain.ai.dto.ExtractionReviewStatus status = result.getManagerReviewStatus();
            if (status != com.apms.domain.ai.dto.ExtractionReviewStatus.ACCEPTED) {
                throw new BusinessValidationException("ALL_FIELDS_MUST_BE_ACCEPTED");
            }
        }
    }

    private Double resolveCandidateConfidence(CompanyCandidate candidate) {
        if (candidate.getRelationshipConfidenceScore() != null) {
            return candidate.getRelationshipConfidenceScore();
        }

        if (candidate.getRelationshipSuggestion() != null
                && candidate.getRelationshipSuggestion().getConfidence() != null) {
            return candidate.getRelationshipSuggestion().getConfidence();
        }

        List<String> extractionIds = candidate.getExtractionIds();
        if (extractionIds == null || extractionIds.isEmpty()) {
            return null;
        }

        double sum = 0.0;
        int count = 0;
        for (String extractionId : extractionIds) {
            try {
                Double extractionConfidence = averageExtractionConfidence(aiExtractionService.getExtractionById(extractionId));
                if (extractionConfidence != null) {
                    sum += extractionConfidence;
                    count++;
                }
            } catch (RuntimeException ex) {
                log.warn("Cannot resolve extraction confidence for candidateId={}, extractionId={}: {}",
                        candidate.getId(), extractionId, ex.getMessage());
            }
        }

        return count == 0 ? null : sum / count;
    }

    private Double averageExtractionConfidence(AiExtractionCache extraction) {
        if (extraction == null) {
            return null;
        }

        if (extraction.getQualityMetrics() != null
                && extraction.getQualityMetrics().getAverageConfidence() != null) {
            return extraction.getQualityMetrics().getAverageConfidence();
        }

        if (extraction.getFieldResults() == null || extraction.getFieldResults().isEmpty()) {
            return null;
        }

        double sum = 0.0;
        int count = 0;
        for (ExtractionFieldResult field : extraction.getFieldResults().values()) {
            if (field != null && field.getConfidence() != null) {
                sum += field.getConfidence();
                count++;
            }
        }

        return count == 0 ? null : sum / count;
    }
}
