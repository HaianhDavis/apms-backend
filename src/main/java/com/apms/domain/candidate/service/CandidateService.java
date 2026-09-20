package com.apms.domain.candidate.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.CandidateStatus;
import com.apms.common.enums.RelationshipType;
import com.apms.common.enums.TaskStatus;
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

import com.apms.domain.financial.DocumentCompanyValidationStatus;
import com.apms.domain.financial.service.DocumentCompanyMatcher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

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
    private final com.apms.domain.candidate.repository.mongo.CandidateDraftSequenceRepository draftSequenceRepository;
    private final com.apms.domain.audit.service.AuditLogService auditLogService;
    private final DocumentCompanyMatcher companyMatcher;
    private final com.apms.domain.reference.service.IndustryCatalogService industryCatalogService;

    // ─────────────────────────────────────────────
    // CREATE (from AI)
    // ─────────────────────────────────────────────

    public static final java.util.Set<String> STAFF_REVIEWABLE_FIELDS = com.apms.domain.project.fieldapproval.CandidateFieldAccessor.REVIEWABLE_FIELD_PATHS;

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

    public synchronized int getNextDraftSequence(Long taskId) {
        if (taskId == null) return 1;
        String taskIdStr = String.valueOf(taskId);
        List<CompanyCandidate> existing = candidateRepository.findByTaskId(taskId);
        int maxExistingSeq = existing.stream()
                .map(c -> c.getDraftSequence() != null ? c.getDraftSequence() : 0)
                .max(Integer::compareTo)
                .orElse(0);
        if (maxExistingSeq == 0 && !existing.isEmpty()) {
            maxExistingSeq = existing.size();
        }
        com.apms.domain.candidate.CandidateDraftSequence tracked = draftSequenceRepository.findById(taskIdStr).orElse(null);
        int next = Math.max(tracked != null && tracked.getCurrentSequence() != null ? tracked.getCurrentSequence() : 0, maxExistingSeq) + 1;
        draftSequenceRepository.save(new com.apms.domain.candidate.CandidateDraftSequence(taskIdStr, next));
        return next;
    }

    @Transactional
    public CandidateResponse createManualCandidate(Long projectId, Long taskId, Long creatorId) {
        LocalDateTime now = LocalDateTime.now();

        com.apms.domain.project.Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new com.apms.common.exception.ResourceNotFoundException("Project not found: " + projectId));

        com.apms.domain.project.ProjectTask task = projectTaskRepository.findById(taskId)
                .orElseThrow(() -> new com.apms.common.exception.ResourceNotFoundException("Task not found: " + taskId));

        if (!task.getProject().getId().equals(projectId)) {
            throw new com.apms.common.exception.BusinessValidationException("Task does not belong to the specified project");
        }

        if (task.getStatus() == com.apms.common.enums.TaskStatus.DONE ||
            task.getStatus() == com.apms.common.enums.TaskStatus.CANCELLED) {
            throw new com.apms.common.exception.BusinessValidationException("Cannot create candidate for completed or cancelled task");
        }

        if (creatorId != null) {
            boolean isMember = projectRepository.existsByIdAndMembersAccountId(projectId, creatorId)
                    || projectRepository.existsByIdAndCreatedByAccountId(projectId, creatorId);
            if (!isMember) {
                throw new org.springframework.security.access.AccessDeniedException("User is not a member of this project");
            }
            if (task.getAssignedToAccount() != null && !task.getAssignedToAccount().getId().equals(creatorId)) {
                throw new org.springframework.security.access.AccessDeniedException("Staff can only access tasks assigned to them");
            }
        }

        // Check whether an active manual DRAFT already exists for this task
        List<CompanyCandidate> existingManualDrafts = candidateRepository.findByTaskId(taskId).stream()
                .filter(c -> c.getStatus() == CandidateStatus.DRAFT &&
                        c.getExtractionSource() != null &&
                        "MANUAL".equalsIgnoreCase(c.getExtractionSource().getExtractionMethod()))
                .sorted((a, b) -> {
                    int seqA = a.getDraftSequence() != null ? a.getDraftSequence() : 0;
                    int seqB = b.getDraftSequence() != null ? b.getDraftSequence() : 0;
                    return Integer.compare(seqB, seqA);
                })
                .toList();

        if (!existingManualDrafts.isEmpty()) {
            return toResponse(existingManualDrafts.get(0));
        }

        int nextSeq = getNextDraftSequence(taskId);
        String draftName = "Draft " + nextSeq;

        CompanyCandidate candidate = CompanyCandidate.builder()
                .projectId(String.valueOf(projectId))
                .taskId(taskId)
                .draftName(draftName)
                .draftSequence(nextSeq)
                .status(CandidateStatus.DRAFT)
                .revisionNumber(1)
                .documentVersion(0L)
                .detectedCompanyName(project.getTargetCompanyName())
                .companyMatchStatus(DocumentCompanyValidationStatus.MATCH)
                .companyMatchConfirmed(true)
                .identity(CompanyCandidate.Identity.builder()
                        .legalName(project.getTargetCompanyName())
                        .taxCode(project.getTargetCompanyTaxCode())
                        .build())
                .business(CompanyCandidate.Business.builder()
                        .industries(new java.util.ArrayList<>())
                        .products(new java.util.ArrayList<>())
                        .markets(new java.util.ArrayList<>())
                        .targetCustomers(new java.util.ArrayList<>())
                        .build())
                .companySize(CompanyCandidate.CompanySize.builder().build())
                .contact(CompanyCandidate.Contact.builder()
                        .emails(new java.util.ArrayList<>())
                        .phones(new java.util.ArrayList<>())
                        .addresses(new java.util.ArrayList<>())
                        .build())
                .insights(CompanyCandidate.Insights.builder()
                        .strengths(new java.util.ArrayList<>())
                        .weaknesses(new java.util.ArrayList<>())
                        .opportunities(new java.util.ArrayList<>())
                        .threats(new java.util.ArrayList<>())
                        .build())
                .financial(new com.apms.domain.company.model.FinancialInfo())
                .innovation(new com.apms.domain.company.model.InnovationInfo())
                .market(new com.apms.domain.company.model.MarketInfo())
                .risk(new com.apms.domain.company.model.RiskInfo())
                .compliance(new com.apms.domain.company.model.ComplianceInfo())
                .fieldEvidence(new java.util.HashMap<>())
                .fieldResults(new java.util.HashMap<>())
                .qualityStatus(com.apms.domain.ai.dto.ExtractionQualityStatus.PENDING_VALIDATION)
                .qualityMetrics(null)
                .extractionSource(CompanyCandidate.ExtractionSource.builder()
                        .extractionMethod("MANUAL")
                        .build())
                .metadata(CompanyCandidate.Metadata.builder()
                        .createdBy(String.valueOf(creatorId))
                        .createdAt(now)
                        .lastModifiedBy(String.valueOf(creatorId))
                        .updatedAt(now)
                        .build())
                .build();

        CompanyCandidate saved = candidateRepository.save(candidate);

        return toResponse(saved);
    }

    private CandidateResponse buildAndSaveCandidate(String projectId, String importJobId, String rawDocumentId, ExtractedCompanyData extractedData,
                                                    java.util.Map<String, ExtractionFieldResult> sourceFieldResults, Long creatorId) {
        // 2. Map extracted data to Candidate flexible embedded documents
        com.apms.domain.project.Project project = projectRepository.findById(Long.valueOf(projectId))
                .orElseThrow(() -> new com.apms.common.exception.ResourceNotFoundException("Project not found: " + projectId));

        CompanyCandidate.Identity identity = CompanyCandidate.Identity.builder()
                .legalName(project.getTargetCompanyName()) // authoritative from Project
                .tradeName(extractedData.getTradeName())
                .taxCode(project.getTargetCompanyTaxCode()) // authoritative from Project
                .build();

        CompanyCandidate.Business business = CompanyCandidate.Business.builder()
                .industries(extractedData.getIndustries())
                .businessModel(extractedData.getBusinessModel())
                .foundedYear(extractedData.getFoundedYear())
                .companyDescription(extractedData.getCompanyDescription())
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
                .employeeCount(extractedData.getEmployeeCount())
                .build();

        CompanyCandidate.Contact contact = CompanyCandidate.Contact.builder()
                .website(extractedData.getWebsite())
                .emails(extractedData.getEmail())
                .phones(extractedData.getPhone())
                .addresses(extractedData.getAddresses() != null && !extractedData.getAddresses().isEmpty()
                        ? CompanyCandidate.Contact.toAddressObjects(extractedData.getAddresses())
                        : (extractedData.getAddress() != null && !extractedData.getAddress().isBlank()
                                ? CompanyCandidate.Contact.toAddressObjects(java.util.List.of(extractedData.getAddress().trim()))
                                : null))
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

        Long tid = null;
        if (rawDocumentId != null) {
            try {
                RawDocument rd = rawDocumentRepository.findById(rawDocumentId).orElse(null);
                if (rd != null && rd.getTaskId() != null) {
                    tid = Long.valueOf(rd.getTaskId());
                }
            } catch (Exception ignored) {}
        }
        int nextSeq = getNextDraftSequence(tid);
        String draftName = "Draft " + nextSeq;

        String rawDetectedCompanyName = null;
        if (extractedData != null) {
            if (org.springframework.util.StringUtils.hasText(extractedData.getLegalName())) {
                rawDetectedCompanyName = extractedData.getLegalName().trim();
            } else if (org.springframework.util.StringUtils.hasText(extractedData.getTradeName())) {
                rawDetectedCompanyName = extractedData.getTradeName().trim();
            }
        }
        DocumentCompanyValidationStatus companyMatchStatus = companyMatcher.evaluateCompanyMatch(rawDetectedCompanyName, project.getTargetCompanyName());
        boolean companyMatchConfirmed = (companyMatchStatus == DocumentCompanyValidationStatus.MATCH);

        // 3. Create Candidate
        CompanyCandidate candidate = CompanyCandidate.builder()
                .projectId(projectId)
                .importJobId(importJobId)
                .taskId(tid)
                .draftName(draftName)
                .draftSequence(nextSeq)
                .rawDocumentId(rawDocumentId)
                .sourceDocumentIds(resolveSourceDocumentIds(rawDocumentId, null))
                .candidateOrder(1)
                .revisionNumber(1)
                .status(CandidateStatus.DRAFT)
                .detectedCompanyName(rawDetectedCompanyName)
                .companyMatchStatus(companyMatchStatus)
                .companyMatchConfirmed(companyMatchConfirmed)
                .suggestedRelationshipType(suggestedRel)
                .relationshipConfidenceScore(confidence)
                .relationshipSuggestion(suggestion)
                .identity(identity)
                .business(business)
                .companySize(size)
                .contact(contact)
                .insights(null)
                .keyPeople(extractedData.getKeyPeople())
                .financial(null)
                .market(null)
                .innovation(null)
                .risk(null)
                .compliance(null)
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

        if (isManualCandidate(candidate) && request.getContact() != null) {
            String website = request.getContact().getWebsite();
            if (org.springframework.util.StringUtils.hasText(website) && !com.apms.domain.ai.service.AiExtractionQualityService.isValidUrl(website)) {
                throw new BusinessValidationException("INVALID_WEBSITE", "Invalid website URL format: " + website);
            }
            if (request.getContact().getEmails() != null) {
                for (String email : request.getContact().getEmails()) {
                    if (org.springframework.util.StringUtils.hasText(email) && !com.apms.domain.ai.service.AiExtractionQualityService.isValidEmail(email)) {
                        throw new BusinessValidationException("INVALID_EMAIL", "Invalid email format: " + email);
                    }
                }
            }
            if (request.getContact().getPhones() != null) {
                for (String phone : request.getContact().getPhones()) {
                    if (org.springframework.util.StringUtils.hasText(phone) && !com.apms.domain.ai.service.AiExtractionQualityService.isValidPhone(phone)) {
                        throw new BusinessValidationException("INVALID_PHONE", "Invalid phone format: " + phone);
                    }
                }
            }
        }

        if (request.getIdentity() != null) candidate.setIdentity(request.getIdentity());
        if (request.getBusiness() != null) {
            if (request.getBusiness().getFoundedYear() != null) {
                validateFoundedYear(request.getBusiness().getFoundedYear());
            }
            candidate.setBusiness(request.getBusiness());
        }
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
    public CandidateResponse renameCandidateDraft(String candidateId, String newDraftName, Long currentUserId) {
        if (newDraftName == null || newDraftName.trim().isBlank()) {
            throw new BusinessValidationException("Draft name cannot be blank");
        }
        String trimmedName = newDraftName.trim();
        if (trimmedName.length() > 200) {
            throw new BusinessValidationException("Draft name cannot exceed 200 characters");
        }

        CompanyCandidate candidate = findCandidateOrThrow(candidateId);

        if (candidate.getStatus() != CandidateStatus.DRAFT && candidate.getStatus() != CandidateStatus.REVISION_REQUIRED) {
            throw new com.apms.common.exception.BusinessConflictException("Draft name can only be edited when candidate is in DRAFT or REVISION_REQUIRED status");
        }

        if (candidate.getTaskId() != null) {
            com.apms.domain.project.ProjectTask task = projectTaskRepository.findById(candidate.getTaskId()).orElse(null);
            if (task != null && task.getStatus() == TaskStatus.IN_REVIEW) {
                throw new com.apms.common.exception.BusinessConflictException("Cannot rename draft while task is submitted for review");
            }
        }

        candidate.setDraftName(trimmedName);
        if (candidate.getMetadata() != null) {
            candidate.getMetadata().setLastModifiedBy(String.valueOf(currentUserId));
            candidate.getMetadata().setUpdatedAt(LocalDateTime.now());
        }

        candidate = candidateRepository.save(candidate);
        auditLogService.log(currentUserId, AuditAction.CORRECT_CANDIDATE, "CompanyCandidate", candidateId,
                "Renamed candidate draft to '" + trimmedName + "'");

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
                .foundedYear(incoming.getFoundedYear() != null ? incoming.getFoundedYear() : current.getFoundedYear())
                .companyDescription(incoming.getCompanyDescription() != null ? incoming.getCompanyDescription() : current.getCompanyDescription())
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

        if (requiresCompanyConfirmation(candidate)) {
            throw new BusinessValidationException("COMPANY_MATCH_UNCONFIRMED", "Candidate requires company match confirmation before submission.");
        }

        if (isManualCandidate(candidate)) {
            validateManualCandidateSubmission(candidate);
        } else {
            validateAiCandidateSubmission(candidate);
        }

        boolean isFirstSubmission = (candidate.getFieldApprovals() == null || candidate.getFieldApprovals().isEmpty());

        if (isFirstSubmission) {
            candidate.setRevisionNumber(1);
            candidate.setFieldApprovals(new java.util.ArrayList<>());
            if (isManualCandidate(candidate)) {
                for (String path : CandidateFieldAccessor.REVIEWABLE_FIELD_PATHS) {
                    candidate.getFieldApprovals().add(
                            com.apms.domain.project.fieldapproval.FieldApprovalRecord.builder()
                                    .fieldPath(path)
                                    .status(com.apms.common.enums.FieldApprovalStatus.PENDING_REVIEW)
                                    .changedInRevision(1)
                                    .build()
                    );
                }
            } else {
                fieldApprovalService.initializeFirstSubmission(candidate, CandidateFieldAccessor.getReviewableDefinitions(), candidate.getFieldApprovals());
            }
        } else {
            // Resubmission: sendBackCandidate already opened the next revision. Do not increment again.
            if (candidate.getRevisionNumber() == null) {
                candidate.setRevisionNumber(1);
            }
            if (candidate.getFieldApprovals() != null) {
                java.util.Map<String, com.apms.domain.project.fieldapproval.FieldApprovalRecord> map =
                        com.apms.domain.project.fieldapproval.FieldApprovalUtils.toMap(candidate.getFieldApprovals());
                java.util.Set<String> changedSet = candidate.getChangedFieldPaths() != null
                        ? new java.util.HashSet<>(candidate.getChangedFieldPaths())
                        : java.util.Collections.emptySet();

                for (com.apms.domain.project.fieldapproval.FieldApprovalRecord record : candidate.getFieldApprovals()) {
                    if (record == null || record.getFieldPath() == null) {
                        continue;
                    }
                    String path = record.getFieldPath();
                    boolean isReturned = isReturnedApprovalStatus(record.getStatus());
                    boolean isChanged = changedSet.contains(path);

                    if (isReturned || isChanged) {
                        if (record.getPendingValue() == null) {
                            com.apms.domain.ai.dto.ExtractionFieldResult existingFr = candidate.getFieldResults() != null
                                    ? candidate.getFieldResults().get(com.apms.domain.ai.service.FieldKeyCodec.encode(path))
                                    : null;
                            record.setPendingValue(resolveCurrentFieldValue(existingFr, candidate, path));
                        }
                        if (record.getStatus() != com.apms.common.enums.FieldApprovalStatus.PENDING_REVIEW) {
                            record.setPreviousStatus(record.getStatus());
                            record.setPreviousComment(record.getComment());
                            if (record.getReviewedRevision() != null) {
                                record.setPreviousReviewedRevision(record.getReviewedRevision());
                            }
                        }
                        record.setStatus(com.apms.common.enums.FieldApprovalStatus.PENDING_REVIEW);
                        record.setComment(null);
                        record.setReviewedRevision(null);
                        record.setReviewedByAccountId(null);
                        record.setReviewedAt(null);
                        record.setApprovedValueHash(null);
                        record.setChangedInRevision(candidate.getRevisionNumber());

                        if (candidate.getFieldResults() != null) {
                            com.apms.domain.ai.dto.ExtractionFieldResult fr =
                                    candidate.getFieldResults().get(com.apms.domain.ai.service.FieldKeyCodec.encode(path));
                            if (fr != null) {
                                if (record.getPreviousStatus() != null) {
                                    fr.setPreviousManagerReviewStatus(toExtractionReviewStatus(record.getPreviousStatus()));
                                    fr.setPreviousManagerReviewComment(record.getPreviousComment());
                                    fr.setPreviousReviewedRevision(record.getPreviousReviewedRevision());
                                    fr.setPreviousSubmittedValue(record.getPendingValue());
                                }
                                fr.setManagerReviewStatus(com.apms.domain.ai.dto.ExtractionReviewStatus.PENDING);
                                fr.setManagerReviewComment(null);
                                fr.setManagerReviewedByUserId(null);
                                fr.setManagerReviewedAt(null);
                                fr.setReviewedRevision(null);
                                fr.setChangedInRevision(candidate.getRevisionNumber());
                                fr.setSubmittedRound(candidate.getRevisionNumber());
                                fr.setResubmittedInCurrentRound(true);
                            }
                        }
                    }
                }

                for (String path : changedSet) {
                    if (CandidateFieldAccessor.REVIEWABLE_FIELD_PATHS.contains(path) && !map.containsKey(path)) {
                        com.apms.domain.project.fieldapproval.FieldApprovalRecord newRec =
                                com.apms.domain.project.fieldapproval.FieldApprovalRecord.builder()
                                        .fieldPath(path)
                                        .status(com.apms.common.enums.FieldApprovalStatus.PENDING_REVIEW)
                                        .changedInRevision(candidate.getRevisionNumber())
                                        .build();
                        candidate.getFieldApprovals().add(newRec);
                        map.put(path, newRec);
                    }
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
    public void cancelCandidateSubmission(String candidateId) {
        CompanyCandidate candidate = findCandidateOrThrow(candidateId);
        if (candidate.getStatus() == CandidateStatus.PENDING_REVIEW) {
            boolean hadManagerReview = candidate.getFieldApprovals() != null && candidate.getFieldApprovals().stream()
                    .anyMatch(f -> f.getReviewedByAccountId() != null
                            || f.getStatus() == com.apms.common.enums.FieldApprovalStatus.APPROVED
                            || f.getStatus() == com.apms.common.enums.FieldApprovalStatus.REVISION_REQUIRED
                            || f.getStatus() == com.apms.common.enums.FieldApprovalStatus.REJECTED);

            if (hadManagerReview) {
                candidate.setStatus(CandidateStatus.REVISION_REQUIRED);
            } else {
                candidate.setStatus(CandidateStatus.DRAFT);
                candidate.setFieldApprovals(new java.util.ArrayList<>());
            }
            if (candidate.getMetadata() != null) {
                candidate.getMetadata().setUpdatedAt(LocalDateTime.now());
            }
            candidateRepository.save(candidate);
            log.info("Candidate submission cancelled: id={}, restoredStatus={}", candidateId, candidate.getStatus());
        }
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
        validateCandidateFinalApprovalEligibility(candidate, decision);
    }

    @Transactional(readOnly = true)
    public com.apms.domain.project.dto.ReviewSummaryResponse getReviewSummary(String candidateId, Integer submittedRevisionNumber) {
        CompanyCandidate candidate = findCandidateOrThrow(candidateId);

        boolean readyForApproval = true;
        java.util.List<String> blockingFields = new java.util.ArrayList<>();

        if (isManualCandidate(candidate)) {
            String legalName = candidate.getIdentity() != null ? candidate.getIdentity().getLegalName() : null;
            if (!org.springframework.util.StringUtils.hasText(legalName) && candidate.getProjectId() != null) {
                try {
                    legalName = projectRepository.findById(Long.valueOf(candidate.getProjectId()))
                            .map(com.apms.domain.project.Project::getTargetCompanyName)
                            .orElse(null);
                } catch (Exception ignored) {}
            }
            if (!org.springframework.util.StringUtils.hasText(legalName)) {
                readyForApproval = false;
            }
            java.util.Map<String, com.apms.domain.project.fieldapproval.FieldApprovalRecord> approvalMap =
                    com.apms.domain.project.fieldapproval.FieldApprovalUtils.toMap(candidate.getFieldApprovals());
            for (String fieldPath : CandidateFieldAccessor.REVIEWABLE_FIELD_PATHS) {
                EffectiveFieldDecision eff = resolveEffectiveFieldDecision(fieldPath, approvalMap, candidate.getFieldResults());
                if (eff != EffectiveFieldDecision.APPROVED) {
                    readyForApproval = false;
                    blockingFields.add(fieldPath);
                }
            }
        } else {
            if (candidate.getFieldApprovals() != null) {
                for (com.apms.domain.project.fieldapproval.FieldApprovalRecord record : candidate.getFieldApprovals()) {
                    if (record.getStatus() == com.apms.common.enums.FieldApprovalStatus.PENDING_REVIEW || record.getStatus() == com.apms.common.enums.FieldApprovalStatus.REVISION_REQUIRED) {
                        readyForApproval = false;
                        blockingFields.add(record.getFieldPath());
                    }
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

        validateCandidateFinalApprovalEligibility(candidate, com.apms.common.enums.ReviewDecision.APPROVE);

        if (candidate.getStatus() != CandidateStatus.PENDING_REVIEW && candidate.getStatus() != CandidateStatus.APPROVED) {
            throw new BusinessValidationException("Only PENDING_REVIEW candidates can be approved");
        }
        validateCandidateSourceDocumentsAreResearch(candidate);

        // Prevent owner organization from being evaluated as a target
        if (candidate.getDeduplication() != null && ownerOrganizationService.isOwnerCompany(candidate.getDeduplication().getExistingProfileIdMatch())) {
            throw new BusinessValidationException("The Owner Organization cannot be selected as a project target.");
        }

        CandidateStatus previousStatus = candidate.getStatus();
        com.apms.domain.ai.dto.ExtractionQualityStatus previousQuality = candidate.getQualityStatus();
        CandidateStatus previousLifecycleStatus = candidate.getLifecycle() != null ? candidate.getLifecycle().getStatus() : null;

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

        try {
            // Publish event for Profile & Graph downstream handling
            eventPublisher.publishEvent(new CandidateApprovedEvent(candidateId, candidate.getProjectId(), finalType, confidence));
        } catch (Exception e) {
            // Rollback MongoDB save since it doesn't participate in Spring's JPA transaction manager by default
            candidate.setStatus(previousStatus);
            candidate.setQualityStatus(previousQuality);
            if (candidate.getLifecycle() != null) {
                candidate.getLifecycle().setStatus(previousLifecycleStatus);
            }
            candidateRepository.save(candidate);
            throw e;
        }

        CompanyCandidate refreshed = candidateRepository.findById(candidateId).orElse(candidate);
        if (refreshed.getBusiness() != null && refreshed.getBusiness().getIndustries() != null && !refreshed.getBusiness().getIndustries().isEmpty()) {
            String profileId = refreshed.getLifecycle() != null ? refreshed.getLifecycle().getConvertedCompanyProfileId() : null;
            List<String> canonical = industryCatalogService.upsertApprovedIndustries(
                    refreshed.getBusiness().getIndustries(),
                    refreshed.getId(),
                    profileId,
                    reviewerId
            );
            refreshed.getBusiness().setIndustries(canonical);
            refreshed = candidateRepository.save(refreshed);
        }
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

        if (request.getFields() != null && !request.getFields().isEmpty()) {
            boolean hasManagerUpdates = request.getFields().values().stream()
                    .anyMatch(u -> u != null && (u.isManager() || u.getManagerReviewStatus() != null));
            if (hasManagerUpdates) {
                boolean activeReview = candidate.getStatus() == CandidateStatus.PENDING_REVIEW
                        || candidate.getStatus() == CandidateStatus.CORRECTED;
                if (!activeReview) {
                    throw new BusinessValidationException(
                            "Field decisions can only be modified during an active candidate review."
                    );
                }
            }
        }

        if (candidate.getStatus() == CandidateStatus.APPROVED) {
            throw new BusinessValidationException("Candidate has already been approved and cannot be modified.");
        }

        if (candidate.getFieldResults() == null) {
            candidate.setFieldResults(new java.util.HashMap<>());
        }

        if (request.getFields() != null) {
            for (java.util.Map.Entry<String, com.apms.domain.candidate.dto.CandidateReviewRequest.FieldReviewUpdate> entry : request.getFields().entrySet()) {
                String fieldPath = entry.getKey();
                if ("contact.address".equals(fieldPath)) {
                    fieldPath = "contact.addresses";
                }
                if (!STAFF_REVIEWABLE_FIELDS.contains(fieldPath) && CandidateFieldAccessor.getDefinition(fieldPath) == null) {
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

                boolean isManagerAction = update.isManager() || update.getManagerReviewStatus() != null;
                if (isManagerAction) {
                    if (update.getManagerReviewStatus() == com.apms.domain.ai.dto.ExtractionReviewStatus.PENDING) {
                        undoManagerFieldDecision(candidate, fieldPath, fieldResult, userId);
                    } else {
                        if (update.getManagerReviewStatus() == com.apms.domain.ai.dto.ExtractionReviewStatus.NEEDS_REVIEW
                                || update.getManagerReviewStatus() == com.apms.domain.ai.dto.ExtractionReviewStatus.REJECTED) {
                            if (!org.springframework.util.StringUtils.hasText(update.getManagerReviewComment())) {
                                throw new BusinessValidationException("Lý do yêu cầu chỉnh sửa là bắt buộc.");
                            }
                        }

                        if (fieldResult != null) {
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
                            fieldResult.setReviewedRevision(candidate.getRevisionNumber());
                        }
                    }
                } else {
                    com.apms.domain.project.fieldapproval.FieldApprovalRecord approval = findFieldApproval(candidate, fieldPath);
                    if ((approval != null && approval.getStatus() == com.apms.common.enums.FieldApprovalStatus.APPROVED)
                            || (fieldResult != null && fieldResult.getManagerReviewStatus() == com.apms.domain.ai.dto.ExtractionReviewStatus.ACCEPTED)) {
                        throw new BusinessValidationException("This field has already been approved by the Manager and cannot be modified.");
                    }

                    if (isManualCandidate(candidate)) {
                        validateManualFieldFormat(fieldPath, update.isReviewedValuePresent() ? update.getReviewedValue() : null);

                        Object currentVal = readEmbeddedField(candidate, fieldPath);
                        Object newVal = update.isReviewedValuePresent() ? update.getReviewedValue() : currentVal;
                        boolean hasOld = isFieldValPresent(currentVal);
                        boolean hasNew = isFieldValPresent(newVal);

                        if (!hasOld && hasNew) {
                            fieldResult.setStaffReviewStatus(com.apms.domain.ai.dto.StaffFieldReviewStatus.ADDED);
                        } else if (hasOld && !hasNew) {
                            fieldResult.setStaffReviewStatus(com.apms.domain.ai.dto.StaffFieldReviewStatus.REMOVED);
                        } else if (hasOld && hasNew) {
                            fieldResult.setStaffReviewStatus(com.apms.domain.ai.dto.StaffFieldReviewStatus.EDITED);
                        } else {
                            fieldResult.setStaffReviewStatus(com.apms.domain.ai.dto.StaffFieldReviewStatus.PENDING);
                        }
                    } else {
                        if (update.getStaffReviewStatus() != null) {
                            fieldResult.setStaffReviewStatus(update.getStaffReviewStatus());
                        }
                    }

                    if (update.isReviewedValuePresent() || update.getStaffReviewStatus() == com.apms.domain.ai.dto.StaffFieldReviewStatus.EDITED) {
                        if (!isManualCandidate(candidate) && update.getStaffReviewStatus() == com.apms.domain.ai.dto.StaffFieldReviewStatus.EDITED && !update.isReviewedValuePresent()) {
                            throw new BusinessValidationException("EDITED review status requires a reviewedValue.");
                        }
                        fieldResult.setStaffReviewedValue(update.getReviewedValue());
                    }
                    
                    boolean hasStaffEdited = update.getStaffReviewStatus() == com.apms.domain.ai.dto.StaffFieldReviewStatus.EDITED
                            || update.getStaffReviewStatus() == com.apms.domain.ai.dto.StaffFieldReviewStatus.ADDED
                            || update.getStaffReviewStatus() == com.apms.domain.ai.dto.StaffFieldReviewStatus.REMOVED
                            || update.getStaffReviewStatus() == com.apms.domain.ai.dto.StaffFieldReviewStatus.CONFIRMED
                            || fieldResult.getStaffReviewStatus() == com.apms.domain.ai.dto.StaffFieldReviewStatus.ADDED
                            || fieldResult.getStaffReviewStatus() == com.apms.domain.ai.dto.StaffFieldReviewStatus.EDITED
                            || fieldResult.getStaffReviewStatus() == com.apms.domain.ai.dto.StaffFieldReviewStatus.REMOVED
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
                } else if (fieldResult != null && fieldResult.getStaffReviewedValue() != null) {
                    val = fieldResult.getStaffReviewedValue();
                } else if (fieldResult != null) {
                    val = fieldResult.getValue();
                } else {
                    val = readEmbeddedField(candidate, fieldPath);
                }

                // Sync to embedded models
                syncEmbeddedField(candidate, fieldPath, val);
                if (isManagerAction) {
                    if (update.getManagerReviewStatus() != com.apms.domain.ai.dto.ExtractionReviewStatus.PENDING) {
                        syncFieldApprovalFromManagerReview(candidate, fieldPath, update.getManagerReviewStatus(), update.getManagerReviewComment(), userId);
                    }
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

    private void undoManagerFieldDecision(CompanyCandidate candidate, String fieldPath, ExtractionFieldResult fieldResult, Long userId) {
        com.apms.domain.project.fieldapproval.FieldApprovalRecord approval = findFieldApproval(candidate, fieldPath);

        boolean isApproved = (fieldResult != null && fieldResult.getManagerReviewStatus() == com.apms.domain.ai.dto.ExtractionReviewStatus.ACCEPTED)
                || (approval != null && approval.getStatus() == com.apms.common.enums.FieldApprovalStatus.APPROVED);
        boolean isRejected = (fieldResult != null && (fieldResult.getManagerReviewStatus() == com.apms.domain.ai.dto.ExtractionReviewStatus.REJECTED || fieldResult.getManagerReviewStatus() == com.apms.domain.ai.dto.ExtractionReviewStatus.NEEDS_REVIEW))
                || (approval != null && (approval.getStatus() == com.apms.common.enums.FieldApprovalStatus.REJECTED || approval.getStatus() == com.apms.common.enums.FieldApprovalStatus.REVISION_REQUIRED));

        if (!isApproved && !isRejected) {
            throw new BusinessValidationException(
                    "Only an approved or rejected field can be returned to Pending Review."
            );
        }

        // Multi-round check: Undo is only allowed if decision was explicitly made in the CURRENT active round
        Integer reviewedRev = approval != null && approval.getReviewedRevision() != null
                ? approval.getReviewedRevision()
                : (fieldResult != null ? fieldResult.getReviewedRevision() : null);
        if (candidate.getRevisionNumber() != null && candidate.getRevisionNumber() > 1) {
            if (reviewedRev == null || !reviewedRev.equals(candidate.getRevisionNumber())) {
                throw new BusinessValidationException("CANNOT_UNDO_PRIOR_ROUND_DECISION",
                        "Cannot undo decision from a previous review round. Field was reviewed in revision "
                                + (reviewedRev != null ? reviewedRev : "prior") + ", but current revision is " + candidate.getRevisionNumber());
            }
        }

        // 1. Reset ExtractionFieldResult current decision
        if (fieldResult != null) {
            fieldResult.setManagerReviewStatus(com.apms.domain.ai.dto.ExtractionReviewStatus.PENDING);
            fieldResult.setManagerReviewedByUserId(null);
            fieldResult.setManagerReviewedAt(null);
            fieldResult.setManagerReviewComment(null);
            fieldResult.setReviewedRevision(null);
        }

        // 2. Synchronize FieldApprovalRecord
        if (approval != null) {
            approval.setStatus(com.apms.common.enums.FieldApprovalStatus.PENDING_REVIEW);
            approval.setReviewedRevision(null);
            approval.setReviewedByAccountId(null);
            approval.setReviewedAt(null);
            approval.setComment(null);
            approval.setApprovedValueHash(null);
            // Multi-round isolation: previousStatus, previousComment, changedInRevision remain untouched!
        } else if (candidate.getFieldApprovals() != null) {
            approval = com.apms.domain.project.fieldapproval.FieldApprovalRecord.builder()
                    .fieldPath(fieldPath)
                    .changedInRevision(candidate.getRevisionNumber())
                    .status(com.apms.common.enums.FieldApprovalStatus.PENDING_REVIEW)
                    .build();
            candidate.getFieldApprovals().add(approval);
        }

        // 3. Log Audit event
        AuditAction action = isApproved ? AuditAction.FIELD_UNDO_APPROVAL : AuditAction.FIELD_UNDO_REJECTION;
        auditLogService.log(userId, action, "CompanyCandidate", candidate.getId(),
                "Manager undid " + (isApproved ? "approval" : "rejection") + " for field " + fieldPath + " (returned to PENDING_REVIEW)");
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
        if (fieldResult != null && fieldResult.getStaffReviewedValue() != null) {
            return fieldResult.getStaffReviewedValue();
        }
        if (fieldResult != null && fieldResult.getValue() != null) {
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
                if (isProjectControlledIdentityField(fieldPath)) {
                    applyProjectControlledFieldDefaults(copy, readEmbeddedField(candidate, fieldPath));
                } else if (copy.getValue() == null) {
                    copy.setValue(readEmbeddedField(candidate, fieldPath));
                }
                if (!isProjectControlledIdentityField(fieldPath)) {
                    copy.setStaffReviewedValue(null);
                    copy.setStaffReviewStatus(com.apms.domain.ai.dto.StaffFieldReviewStatus.PENDING);
                    copy.setManagerReviewStatus(com.apms.domain.ai.dto.ExtractionReviewStatus.PENDING);
                }
                initialized.put(com.apms.domain.ai.service.FieldKeyCodec.encode(fieldPath), copy);
            });
        }

        for (String fieldPath : STAFF_REVIEWABLE_FIELDS) {
            String mapKey = com.apms.domain.ai.service.FieldKeyCodec.encode(fieldPath);
            initialized.computeIfAbsent(mapKey, ignored -> {
                Object value = readEmbeddedField(candidate, fieldPath);
                ExtractionFieldResult result = ExtractionFieldResult.builder()
                        .fieldName(fieldPath)
                        .value(value)
                        .staffReviewStatus(com.apms.domain.ai.dto.StaffFieldReviewStatus.PENDING)
                        .managerReviewStatus(com.apms.domain.ai.dto.ExtractionReviewStatus.PENDING)
                        .build();
                if (isProjectControlledIdentityField(fieldPath)) {
                    applyProjectControlledFieldDefaults(result, value);
                }
                return result;
            });
        }

        return initialized;
    }

    private boolean isProjectControlledIdentityField(String fieldPath) {
        return "identity.legalName".equals(fieldPath) || "identity.taxCode".equals(fieldPath);
    }

    private void applyProjectControlledFieldDefaults(ExtractionFieldResult result, Object value) {
        result.setValue(value);
        result.setNormalizedValue(value);
        result.setConfidence(1.0);
        result.setEvidenceText(null);
        result.setSourceDocumentIds(null);
        result.setPageNumber(null);
        result.setValidationStatus(com.apms.domain.ai.dto.ExtractionValidationStatus.PASS);
        result.setValidationMessages("Provided by manager at project creation.");
        result.setStaffReviewedValue(value);
        result.setStaffReviewStatus(com.apms.domain.ai.dto.StaffFieldReviewStatus.CONFIRMED);
        result.setManagerReviewStatus(com.apms.domain.ai.dto.ExtractionReviewStatus.ACCEPTED);
    }

    private Object readEmbeddedField(CompanyCandidate candidate, String fieldName) {
        if (candidate == null || fieldName == null) return null;
        return switch (fieldName) {
            case "identity.legalName" -> candidate.getIdentity() != null ? candidate.getIdentity().getLegalName() : null;
            case "identity.tradeName" -> candidate.getIdentity() != null ? candidate.getIdentity().getTradeName() : null;
            case "identity.taxCode" -> candidate.getIdentity() != null ? candidate.getIdentity().getTaxCode() : null;
            case "contact.addresses" -> {
                if (candidate.getContact() == null) {
                    yield null;
                }
                List<String> list = candidate.getContact().getEffectiveAddressStrings();
                yield list.isEmpty() ? null : list;
            }
            case "contact.address" -> {
                if (candidate.getContact() == null) {
                    yield null;
                }
                List<String> list = candidate.getContact().getEffectiveAddressStrings();
                yield list.isEmpty() ? null : list.get(0);
            }
            case "contact.website" -> candidate.getContact() != null ? candidate.getContact().getWebsite() : null;
            case "contact.emails" -> candidate.getContact() != null ? candidate.getContact().getEmails() : null;
            case "contact.phones" -> candidate.getContact() != null ? candidate.getContact().getPhones() : null;
            case "business.businessModel" -> candidate.getBusiness() != null ? candidate.getBusiness().getBusinessModel() : null;
            case "business.industries" -> candidate.getBusiness() != null ? candidate.getBusiness().getIndustries() : null;
            case "business.foundedYear" -> candidate.getBusiness() != null ? candidate.getBusiness().getFoundedYear() : null;
            case "business.companyDescription" -> candidate.getBusiness() != null ? candidate.getBusiness().getCompanyDescription() : null;
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
                case "business.foundedYear":
                    if (value != null) {
                        validateFoundedYear(value);
                    }
                    candidate.getBusiness().setFoundedYear(value instanceof Number ? ((Number) value).intValue() : (value instanceof String s && !s.trim().isEmpty() ? Integer.parseInt(s.trim()) : null));
                    break;
                case "business.companyDescription":
                    candidate.getBusiness().setCompanyDescription((String) value);
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
                case "contact.addresses":
                case "contact.address":
                    List<String> normalizedAddrs = CandidateFieldAccessor.normalizeAddressList(value);
                    candidate.getContact().setAddresses(normalizedAddrs.isEmpty() ? null : CompanyCandidate.Contact.toAddressObjects(normalizedAddrs));
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
        record.setComment(approvalStatus == com.apms.common.enums.FieldApprovalStatus.PENDING_REVIEW ? null : comment);
        record.setReviewedRevision(approvalStatus == com.apms.common.enums.FieldApprovalStatus.PENDING_REVIEW ? null : candidate.getRevisionNumber());
        record.setReviewedByAccountId(approvalStatus == com.apms.common.enums.FieldApprovalStatus.PENDING_REVIEW ? null : reviewerId);
        record.setReviewedAt(approvalStatus == com.apms.common.enums.FieldApprovalStatus.PENDING_REVIEW ? null : LocalDateTime.now());
        record.setPendingValue(definition.getGetter().apply(candidate));
        if (approvalStatus == com.apms.common.enums.FieldApprovalStatus.APPROVED) {
            Object approvedValue = definition.getGetter().apply(candidate);
            record.setApprovedValueHash(com.apms.domain.project.fieldapproval.FieldValueHasher.hashValue(
                    approvedValue,
                    definition.isCollection() && !definition.isOrderedCollection()
            ));
        } else {
            record.setApprovedValueHash(null);
        }

        AuditAction action = switch (approvalStatus) {
            case APPROVED -> AuditAction.FIELD_APPROVED;
            case REJECTED -> AuditAction.FIELD_REJECTED;
            case REVISION_REQUIRED -> AuditAction.FIELD_REVISION_REQUESTED;
            case PENDING_REVIEW -> AuditAction.FIELD_REOPENED;
            default -> null;
        };
        if (action != null) {
            auditLogService.log(reviewerId, action, "CompanyCandidate", candidate.getId(),
                    "Field " + fieldPath + " set to " + approvalStatus + " by manager");
        }
    }

    public DocumentCompanyValidationStatus resolveCompanyMatchStatus(CompanyCandidate candidate) {
        if (candidate == null) {
            return DocumentCompanyValidationStatus.UNKNOWN;
        }
        if (candidate.getCompanyMatchStatus() != null) {
            return candidate.getCompanyMatchStatus();
        }
        // Fallback for legacy candidates: evaluate detectedCompanyName vs targetCompanyName
        if (org.springframework.util.StringUtils.hasText(candidate.getDetectedCompanyName()) && candidate.getProjectId() != null) {
            try {
                Long projectId = Long.valueOf(candidate.getProjectId());
                Optional<Project> projectOpt = projectRepository.findById(projectId);
                if (projectOpt.isPresent() && org.springframework.util.StringUtils.hasText(projectOpt.get().getTargetCompanyName())) {
                    return companyMatcher.evaluateCompanyMatch(candidate.getDetectedCompanyName(), projectOpt.get().getTargetCompanyName());
                }
            } catch (Exception e) {
                log.warn("Failed to resolve company match status for candidate {}: {}", candidate.getId(), e.getMessage());
            }
        }
        return DocumentCompanyValidationStatus.UNKNOWN;
    }

    public boolean requiresCompanyConfirmation(CompanyCandidate candidate) {
        if (candidate == null) {
            return false;
        }
        if (isManualCandidate(candidate)) {
            return false;
        }
        DocumentCompanyValidationStatus status = resolveCompanyMatchStatus(candidate);
        if (status == DocumentCompanyValidationStatus.MATCH) {
            return false;
        }
        return !Boolean.TRUE.equals(candidate.getCompanyMatchConfirmed());
    }

    @Transactional
    public CandidateResponse confirmCompanyMatch(String candidateId, boolean confirmed, Long currentUserId) {
        CompanyCandidate candidate = findCandidateOrThrow(candidateId);

        if (candidate.getStatus() == CandidateStatus.APPROVED) {
            throw new BusinessValidationException("CANDIDATE_APPROVED_IMMUTABLE", "Approved candidate is immutable and cannot be modified.");
        }
        if (candidate.getStatus() == CandidateStatus.PENDING_REVIEW) {
            throw new BusinessValidationException("CANDIDATE_IN_REVIEW", "Candidate is currently under review and cannot be modified.");
        }

        if (currentUserId != null && candidate.getProjectId() != null) {
            Long projectId = Long.valueOf(candidate.getProjectId());
            if (!projectRepository.existsByIdAndMembersAccountId(projectId, currentUserId)) {
                throw new AccessDeniedException("User is not a member of this project");
            }
            if (candidate.getTaskId() != null) {
                projectTaskRepository.findById(candidate.getTaskId()).ifPresent(task -> {
                    boolean isAssigned = task.getAssignedToAccount() != null && task.getAssignedToAccount().getId().equals(currentUserId);
                    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                    boolean isManager = auth != null && auth.getAuthorities().stream()
                            .anyMatch(a -> a.getAuthority().equals("ROLE_BUSINESS_DEVELOPMENT_MANAGER")
                                    || a.getAuthority().equals("ROLE_SYSTEM_ADMIN")
                                    || a.getAuthority().equals("ROLE_ADMIN"));
                    if (!isAssigned && !isManager) {
                        throw new AccessDeniedException("Only the assigned staff or manager can confirm company match for this task");
                    }
                });
            }
        }

        if (confirmed) {
            candidate.setCompanyMatchConfirmed(true);
            candidate.setCompanyMatchConfirmedBy(currentUserId);
            candidate.setCompanyMatchConfirmedAt(LocalDateTime.now());
        } else {
            candidate.setCompanyMatchConfirmed(false);
            candidate.setCompanyMatchConfirmedBy(null);
            candidate.setCompanyMatchConfirmedAt(null);
        }

        if (candidate.getMetadata() != null) {
            candidate.getMetadata().setUpdatedAt(LocalDateTime.now());
        }
        candidate = candidateRepository.save(candidate);

        auditLogService.log(
                currentUserId,
                AuditAction.PROJECT_TASK_UPDATED,
                "CompanyCandidate",
                candidateId,
                (confirmed ? "Confirmed" : "Unconfirmed") + " company match for candidate " + (candidate.getDraftName() != null ? candidate.getDraftName() : candidateId)
        );

        return toResponse(candidate);
    }

    private CandidateResponse toResponse(CompanyCandidate c) {
        Double confidenceScore = resolveCandidateConfidence(c);

        java.util.Map<String, com.apms.domain.ai.dto.ExtractionFieldResult> decodedFieldResults = null;
        if (c.getFieldResults() != null) {
            decodedFieldResults = new java.util.HashMap<>();
            for (com.apms.domain.ai.dto.ExtractionFieldResult fr : c.getFieldResults().values()) {
                if (fr.getFieldName() != null) {
                    decodedFieldResults.put(fr.getFieldName(), fr);
                    if ("contact.address".equals(fr.getFieldName())) {
                        decodedFieldResults.putIfAbsent("contact.addresses", fr);
                    }
                }
            }
        }
        decodedFieldResults = applyFieldApprovalsToDecodedResults(c, decodedFieldResults);
        applyProjectControlledIdentityToResponse(c, decodedFieldResults);

        DocumentCompanyValidationStatus resolvedStatus = resolveCompanyMatchStatus(c);
        Boolean isConfirmed = (resolvedStatus == DocumentCompanyValidationStatus.MATCH)
                ? Boolean.TRUE
                : (c.getCompanyMatchConfirmed() != null ? c.getCompanyMatchConfirmed() : false);
        Long confirmedBy = (resolvedStatus == DocumentCompanyValidationStatus.MATCH) ? null : c.getCompanyMatchConfirmedBy();
        LocalDateTime confirmedAt = (resolvedStatus == DocumentCompanyValidationStatus.MATCH) ? null : c.getCompanyMatchConfirmedAt();

        return CandidateResponse.builder()
                .id(c.getId())
                .projectId(c.getProjectId())
                .importJobId(c.getImportJobId())
                .rawDocumentId(c.getRawDocumentId())
                .sourceDocumentIds(resolveSourceDocumentIds(c.getRawDocumentId(), c.getSourceDocumentIds()))
                .candidateOrder(c.getCandidateOrder())
                .revisionNumber(c.getRevisionNumber())
                .currentReviewRound(c.getRevisionNumber() != null ? c.getRevisionNumber() : 1)
                .draftName(c.getDraftName() != null && !c.getDraftName().isBlank() ? c.getDraftName() : (c.getDraftSequence() != null ? "Draft " + c.getDraftSequence() : "Draft"))
                .draftSequence(c.getDraftSequence())
                .draftNumber(c.getDraftSequence())
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
                .companyMatchStatus(resolvedStatus)
                .companyMatchConfirmed(isConfirmed)
                .companyMatchConfirmedBy(confirmedBy)
                .companyMatchConfirmedAt(confirmedAt)
                .detectedCompanyName(c.getDetectedCompanyName())
                .build();
    }

    private void applyProjectControlledIdentityToResponse(
            CompanyCandidate candidate,
            java.util.Map<String, com.apms.domain.ai.dto.ExtractionFieldResult> decodedFieldResults) {
        if (candidate == null || candidate.getProjectId() == null || decodedFieldResults == null) {
            return;
        }
        try {
            Long projectId = Long.valueOf(candidate.getProjectId());
            projectRepository.findById(projectId).ifPresent(project -> {
                if (candidate.getIdentity() == null) {
                    candidate.setIdentity(CompanyCandidate.Identity.builder().build());
                }
                candidate.getIdentity().setLegalName(project.getTargetCompanyName());
                candidate.getIdentity().setTaxCode(project.getTargetCompanyTaxCode());

                applyProjectControlledFieldDefaults(
                        decodedFieldResults.computeIfAbsent("identity.legalName", key -> ExtractionFieldResult.builder()
                                .fieldName("identity.legalName")
                                .build()),
                        project.getTargetCompanyName());
                applyProjectControlledFieldDefaults(
                        decodedFieldResults.computeIfAbsent("identity.taxCode", key -> ExtractionFieldResult.builder()
                                .fieldName("identity.taxCode")
                                .build()),
                        project.getTargetCompanyTaxCode());
            });
        } catch (NumberFormatException ex) {
            log.warn("Cannot resolve project-controlled identity for candidateId={}, projectId={}",
                    candidate.getId(), candidate.getProjectId());
        }
    }

    private java.util.Map<String, com.apms.domain.ai.dto.ExtractionFieldResult> applyFieldApprovalsToDecodedResults(
            CompanyCandidate candidate,
            java.util.Map<String, com.apms.domain.ai.dto.ExtractionFieldResult> decodedFieldResults) {
        if (decodedFieldResults == null) {
            decodedFieldResults = new java.util.HashMap<>();
        }

        if (isManualCandidate(candidate)) {
            for (String fieldPath : STAFF_REVIEWABLE_FIELDS) {
                decodedFieldResults.computeIfAbsent(fieldPath, fp -> {
                    com.apms.domain.ai.dto.ExtractionFieldResult created = new com.apms.domain.ai.dto.ExtractionFieldResult();
                    created.setFieldName(fp);
                    Object val = readEmbeddedField(candidate, fp);
                    if (isMeaningfulValue(val)) {
                        created.setValue(val);
                        created.setProvided(true);
                    } else {
                        created.setValue(null);
                        created.setProvided(false);
                    }
                    created.setManagerReviewStatus(com.apms.domain.ai.dto.ExtractionReviewStatus.PENDING);
                    return created;
                });
            }
        }

        int currentRound = candidate.getRevisionNumber() != null ? candidate.getRevisionNumber() : 1;

        if (candidate.getFieldApprovals() != null && !candidate.getFieldApprovals().isEmpty()) {
            for (com.apms.domain.project.fieldapproval.FieldApprovalRecord record : candidate.getFieldApprovals()) {
                if (record == null || record.getFieldPath() == null) {
                    continue;
                }
                String normalizedPath = "contact.address".equals(record.getFieldPath()) ? "contact.addresses" : record.getFieldPath();
                if (!STAFF_REVIEWABLE_FIELDS.contains(normalizedPath)) {
                    continue;
                }

                com.apms.domain.ai.dto.ExtractionFieldResult fieldResult = decodedFieldResults.computeIfAbsent(normalizedPath, fieldPath -> {
                    com.apms.domain.ai.dto.ExtractionFieldResult created = new com.apms.domain.ai.dto.ExtractionFieldResult();
                    created.setFieldName(fieldPath);
                    Object val = readEmbeddedField(candidate, fieldPath);
                    if (isMeaningfulValue(val)) {
                        created.setValue(val);
                        created.setProvided(true);
                    } else {
                        created.setValue(null);
                        created.setProvided(false);
                    }
                    return created;
                });

                com.apms.domain.ai.dto.ExtractionReviewStatus currentStatus = toExtractionReviewStatus(record.getStatus());
                String currentComment = record.getStatus() == com.apms.common.enums.FieldApprovalStatus.PENDING_REVIEW
                        ? null
                        : record.getComment();

                fieldResult.setManagerReviewStatus(currentStatus);
                fieldResult.setManagerReviewComment(currentComment);
                fieldResult.setManagerReviewedByUserId(record.getReviewedByAccountId());
                fieldResult.setManagerReviewedAt(record.getReviewedAt());
                fieldResult.setChangedInRevision(record.getChangedInRevision());
                fieldResult.setReviewedRevision(record.getReviewedRevision());

                // Canonical current round review context
                Object currentSubmittedVal = resolveCurrentFieldValue(fieldResult, candidate, record.getFieldPath());
                com.apms.domain.ai.dto.FieldReviewDecision currentDecision = com.apms.domain.ai.dto.FieldReviewDecision.builder()
                        .roundNumber(currentRound)
                        .status(currentStatus)
                        .comment(currentComment)
                        .submittedValue(currentSubmittedVal)
                        .reviewedAt(record.getReviewedAt())
                        .reviewedByUserId(record.getReviewedByAccountId())
                        .build();
                fieldResult.setCurrentDecision(currentDecision);

                // Canonical previous round review context: strictly earlier rounds only (< currentRound)
                boolean hasValidPreviousRound = currentRound > 1
                        && record.getPreviousStatus() != null
                        && record.getPreviousStatus() != com.apms.common.enums.FieldApprovalStatus.PENDING_REVIEW;

                if (hasValidPreviousRound) {
                    com.apms.domain.ai.dto.ExtractionReviewStatus prevExtractionStatus = toExtractionReviewStatus(record.getPreviousStatus());
                    Integer prevRev = record.getPreviousReviewedRevision();
                    if (prevRev == null || prevRev >= currentRound) {
                        prevRev = currentRound - 1;
                    }
                    Object prevSubmittedVal = record.getPendingValue() != null
                            ? record.getPendingValue()
                            : fieldResult.getPreviousSubmittedValue();

                    com.apms.domain.ai.dto.FieldReviewDecision previousDecision = com.apms.domain.ai.dto.FieldReviewDecision.builder()
                            .roundNumber(prevRev)
                            .status(prevExtractionStatus)
                            .comment(record.getPreviousComment())
                            .submittedValue(prevSubmittedVal)
                            .reviewedAt(null)
                            .reviewedByUserId(null)
                            .build();

                    fieldResult.setPreviousDecision(previousDecision);
                    fieldResult.setPreviousManagerReviewStatus(prevExtractionStatus);
                    fieldResult.setPreviousManagerReviewComment(record.getPreviousComment());
                    fieldResult.setPreviousReviewedRevision(prevRev);
                    fieldResult.setPreviousSubmittedValue(prevSubmittedVal);
                } else {
                    fieldResult.setPreviousDecision(null);
                    fieldResult.setPreviousManagerReviewStatus(null);
                    fieldResult.setPreviousManagerReviewComment(null);
                    fieldResult.setPreviousReviewedRevision(null);
                    fieldResult.setPreviousSubmittedValue(null);
                }

                fieldResult.setSubmittedRound(currentRound);
                fieldResult.setResubmittedInCurrentRound(currentRound > 1 && record.getPreviousStatus() != null);
            }
        }

        for (com.apms.domain.ai.dto.ExtractionFieldResult fr : decodedFieldResults.values()) {
            if (fr != null) {
                if (fr.getProvided() == null) {
                    Object val = fr.getValue() != null ? fr.getValue() : readEmbeddedField(candidate, fr.getFieldName());
                    boolean meaningful = isMeaningfulValue(val);
                    fr.setProvided(meaningful);
                    if (!meaningful && isManualCandidate(candidate)) {
                        fr.setValue(null);
                    }
                }
                if (fr.getManagerReviewStatus() == null) {
                    fr.setManagerReviewStatus(com.apms.domain.ai.dto.ExtractionReviewStatus.PENDING);
                }
                if (fr.getCurrentDecision() == null) {
                    fr.setCurrentDecision(com.apms.domain.ai.dto.FieldReviewDecision.builder()
                            .roundNumber(currentRound)
                            .status(fr.getManagerReviewStatus())
                            .comment(fr.getManagerReviewComment())
                            .submittedValue(fr.getValue())
                            .reviewedAt(fr.getManagerReviewedAt())
                            .reviewedByUserId(fr.getManagerReviewedByUserId())
                            .build());
                }
                if (currentRound <= 1) {
                    fr.setPreviousDecision(null);
                    fr.setPreviousManagerReviewStatus(null);
                    fr.setPreviousManagerReviewComment(null);
                    fr.setPreviousReviewedRevision(null);
                    fr.setPreviousSubmittedValue(null);
                }
                if (fr.getSubmittedRound() == null) {
                    fr.setSubmittedRound(currentRound);
                }
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

    public enum EffectiveFieldDecision {
        APPROVED,
        CHANGES_REQUESTED,
        PENDING
    }

    public static EffectiveFieldDecision resolveEffectiveFieldDecision(
            String fieldPath,
            java.util.Map<String, com.apms.domain.project.fieldapproval.FieldApprovalRecord> approvals,
            java.util.Map<String, com.apms.domain.ai.dto.ExtractionFieldResult> fieldResults) {

        com.apms.domain.project.fieldapproval.FieldApprovalRecord approval = approvals != null ? approvals.get(fieldPath) : null;
        com.apms.domain.ai.dto.ExtractionFieldResult fr = fieldResults != null ? fieldResults.get(com.apms.domain.ai.service.FieldKeyCodec.encode(fieldPath)) : null;

        if (approval != null) {
            if (approval.getStatus() == com.apms.common.enums.FieldApprovalStatus.APPROVED) {
                return EffectiveFieldDecision.APPROVED;
            }
            if (approval.getStatus() == com.apms.common.enums.FieldApprovalStatus.REVISION_REQUIRED
                    || approval.getStatus() == com.apms.common.enums.FieldApprovalStatus.REJECTED) {
                return EffectiveFieldDecision.CHANGES_REQUESTED;
            }
            if (approval.getStatus() == com.apms.common.enums.FieldApprovalStatus.PENDING_REVIEW
                    || approval.getStatus() == com.apms.common.enums.FieldApprovalStatus.STALE) {
                return EffectiveFieldDecision.PENDING;
            }
        }

        if (fr != null) {
            if (fr.getManagerReviewStatus() == com.apms.domain.ai.dto.ExtractionReviewStatus.ACCEPTED) {
                return EffectiveFieldDecision.APPROVED;
            }
            if (fr.getManagerReviewStatus() == com.apms.domain.ai.dto.ExtractionReviewStatus.NEEDS_REVIEW
                    || fr.getManagerReviewStatus() == com.apms.domain.ai.dto.ExtractionReviewStatus.REJECTED) {
                return EffectiveFieldDecision.CHANGES_REQUESTED;
            }
            if (fr.getManagerReviewStatus() == com.apms.domain.ai.dto.ExtractionReviewStatus.PENDING) {
                return EffectiveFieldDecision.PENDING;
            }
        }

        return EffectiveFieldDecision.PENDING;
    }

    @Transactional
    public CandidateResponse bulkApproveCandidateFields(String projectId, String candidateId, java.util.List<String> requestedFieldPaths, Long userId) {
        CompanyCandidate candidate = findCandidateOrThrow(candidateId);

        if (!projectId.equals(candidate.getProjectId())) {
            throw new BusinessValidationException("Candidate does not belong to project");
        }

        boolean activeReview = candidate.getStatus() == CandidateStatus.PENDING_REVIEW
                || candidate.getStatus() == CandidateStatus.CORRECTED;
        if (!activeReview) {
            throw new BusinessValidationException("Field decisions can only be modified during an active candidate review.");
        }

        if (candidate.getFieldApprovals() == null) {
            candidate.setFieldApprovals(new java.util.ArrayList<>());
        }

        java.util.Map<String, com.apms.domain.project.fieldapproval.FieldApprovalRecord> approvalMap =
                com.apms.domain.project.fieldapproval.FieldApprovalUtils.toMap(candidate.getFieldApprovals());

        java.util.Collection<String> targetPaths = (requestedFieldPaths != null && !requestedFieldPaths.isEmpty())
                ? requestedFieldPaths
                : CandidateFieldAccessor.REVIEWABLE_FIELD_PATHS;

        int approvedCount = 0;
        for (String fieldPath : targetPaths) {
            if (!CandidateFieldAccessor.REVIEWABLE_FIELD_PATHS.contains(fieldPath)) {
                continue;
            }

            EffectiveFieldDecision currentDecision = resolveEffectiveFieldDecision(fieldPath, approvalMap, candidate.getFieldResults());
            if (currentDecision == EffectiveFieldDecision.APPROVED) {
                continue; // ignore fields already approved
            }
            if (currentDecision == EffectiveFieldDecision.CHANGES_REQUESTED) {
                continue; // ignore fields already CHANGES_REQUESTED
            }

            // Only mark effective PENDING fields as APPROVED
            syncFieldApprovalFromManagerReview(candidate, fieldPath, com.apms.domain.ai.dto.ExtractionReviewStatus.ACCEPTED, null, userId);

            if (candidate.getFieldResults() != null) {
                com.apms.domain.ai.dto.ExtractionFieldResult fr =
                        candidate.getFieldResults().get(com.apms.domain.ai.service.FieldKeyCodec.encode(fieldPath));
                if (fr != null) {
                    fr.setManagerReviewStatus(com.apms.domain.ai.dto.ExtractionReviewStatus.ACCEPTED);
                    fr.setManagerReviewedByUserId(userId);
                    fr.setManagerReviewedAt(LocalDateTime.now());
                    fr.setReviewedRevision(candidate.getRevisionNumber());
                }
            }

            auditLogService.log(userId, AuditAction.FIELD_APPROVED, "CompanyCandidate", candidate.getId(),
                    "Field " + fieldPath + " bulk-approved by manager");
            approvedCount++;
        }

        if (candidate.getMetadata() != null) {
            candidate.getMetadata().setLastModifiedBy(String.valueOf(userId));
            candidate.getMetadata().setUpdatedAt(LocalDateTime.now());
        }

        candidate = candidateRepository.save(candidate);
        log.info("Bulk-approved {} pending fields for candidateId={}", approvedCount, candidateId);
        return toResponse(candidate);
    }

    public void validateCandidateFinalApprovalEligibility(CompanyCandidate candidate, com.apms.common.enums.ReviewDecision decision) {
        if (decision == com.apms.common.enums.ReviewDecision.APPROVE) {
            // Domain mandatory business field (with authoritative target fallback)
            String legalName = candidate.getIdentity() != null ? candidate.getIdentity().getLegalName() : null;
            if (!org.springframework.util.StringUtils.hasText(legalName) && candidate.getProjectId() != null) {
                try {
                    legalName = projectRepository.findById(Long.valueOf(candidate.getProjectId()))
                            .map(com.apms.domain.project.Project::getTargetCompanyName)
                            .orElse(null);
                    if (org.springframework.util.StringUtils.hasText(legalName)) {
                        if (candidate.getIdentity() == null) candidate.setIdentity(new CompanyCandidate.Identity());
                        candidate.getIdentity().setLegalName(legalName);
                    }
                } catch (Exception ignored) {}
            }
            if (!org.springframework.util.StringUtils.hasText(legalName)) {
                throw new BusinessValidationException("REQUIRED_CANDIDATE_FIELD_MISSING", "REQUIRED_CANDIDATE_FIELD_MISSING: Company legal name is required");
            }

            if (isManualCandidate(candidate)) {
                java.util.Map<String, com.apms.domain.project.fieldapproval.FieldApprovalRecord> approvals =
                        com.apms.domain.project.fieldapproval.FieldApprovalUtils.toMap(candidate.getFieldApprovals());

                for (String fieldPath : CandidateFieldAccessor.REVIEWABLE_FIELD_PATHS) {
                    EffectiveFieldDecision decisionForField = resolveEffectiveFieldDecision(fieldPath, approvals, candidate.getFieldResults());
                    if (decisionForField == EffectiveFieldDecision.PENDING) {
                        throw new BusinessValidationException("CANDIDATE_HAS_PENDING_FIELDS", "CANDIDATE_HAS_PENDING_FIELDS: Field " + fieldPath + " is PENDING");
                    }
                    if (decisionForField == EffectiveFieldDecision.CHANGES_REQUESTED) {
                        throw new BusinessValidationException("CANDIDATE_HAS_REJECTED_FIELDS", "CANDIDATE_HAS_REJECTED_FIELDS: Field " + fieldPath + " is CHANGES_REQUESTED");
                    }
                }
            } else {
                // AI candidate: preserve existing AI review rules and quality validations
                fieldApprovalService.validateFinalReviewReadiness(candidate, candidate.getFieldApprovals(),
                        com.apms.domain.project.fieldapproval.CandidateFieldAccessor.getAllDefinitions(), decision);
                validateManagerFieldResultsAccepted(candidate);
            }
        } else if (decision == com.apms.common.enums.ReviewDecision.REQUEST_REVISION) {
            fieldApprovalService.validateFinalReviewReadiness(candidate, candidate.getFieldApprovals(),
                    com.apms.domain.project.fieldapproval.CandidateFieldAccessor.getAllDefinitions(), decision);
        }
    }

    public boolean isCandidateFieldSubmitted(CompanyCandidate candidate, String fieldPath) {
        if (!CandidateFieldAccessor.REVIEWABLE_FIELD_PATHS.contains(fieldPath)) {
            return false;
        }
        com.apms.domain.project.fieldapproval.FieldDefinition<CompanyCandidate> def =
                CandidateFieldAccessor.getDefinition(fieldPath);
        if (def != null) {
            Object domainVal = def.getGetter().apply(candidate);
            if (isMeaningfulValue(domainVal)) {
                return true;
            }
        }
        if (candidate.getFieldResults() != null) {
            com.apms.domain.ai.dto.ExtractionFieldResult fr =
                    candidate.getFieldResults().get(com.apms.domain.ai.service.FieldKeyCodec.encode(fieldPath));
            if (fr != null) {
                Object reviewedVal = fr.getStaffReviewedValue() != null ? fr.getStaffReviewedValue() : fr.getValue();
                if (isMeaningfulValue(reviewedVal)) {
                    return true;
                }
            }
        }
        return false;
    }

    public java.util.Set<String> getSubmittedReviewableFieldPaths(CompanyCandidate candidate) {
        java.util.Set<String> submitted = new java.util.LinkedHashSet<>();
        for (String fieldPath : CandidateFieldAccessor.REVIEWABLE_FIELD_PATHS) {
            if (isCandidateFieldSubmitted(candidate, fieldPath)) {
                submitted.add(fieldPath);
            }
        }
        return submitted;
    }

    private boolean isMeaningfulValue(Object val) {
        if (val == null) {
            return false;
        }
        if (val instanceof String str) {
            return org.springframework.util.StringUtils.hasText(str);
        }
        if (val instanceof java.util.Collection<?> col) {
            if (col.isEmpty()) {
                return false;
            }
            for (Object item : col) {
                if (isMeaningfulValue(item)) {
                    return true;
                }
            }
            return false;
        }
        if (val instanceof CompanyCandidate.Product prod) {
            return org.springframework.util.StringUtils.hasText(prod.getName());
        }
        if (val instanceof CompanyCandidate.Address addr) {
            return org.springframework.util.StringUtils.hasText(addr.getFullAddress());
        }
        if (val instanceof Number || val instanceof Boolean) {
            return true;
        }
        return true;
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

            if (isManualCandidate(candidate)) {
                continue;
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

    public static boolean isManualCandidate(CompanyCandidate candidate) {
        return candidate != null
                && candidate.getExtractionSource() != null
                && "MANUAL".equalsIgnoreCase(candidate.getExtractionSource().getExtractionMethod());
    }

    private void validateManualCandidateSubmission(CompanyCandidate candidate) {
        if (candidate.getIdentity() == null || !org.springframework.util.StringUtils.hasText(candidate.getIdentity().getLegalName())) {
            throw new BusinessValidationException("Company legal name is required");
        }

        if (candidate.getBusiness() != null && candidate.getBusiness().getFoundedYear() != null) {
            validateFoundedYear(candidate.getBusiness().getFoundedYear());
        }

        if (candidate.getContact() != null) {
            String website = candidate.getContact().getWebsite();
            if (org.springframework.util.StringUtils.hasText(website) && !com.apms.domain.ai.service.AiExtractionQualityService.isValidUrl(website)) {
                throw new BusinessValidationException("INVALID_WEBSITE", "Invalid website URL format: " + website);
            }

            if (candidate.getContact().getEmails() != null) {
                for (String email : candidate.getContact().getEmails()) {
                    if (org.springframework.util.StringUtils.hasText(email) && !com.apms.domain.ai.service.AiExtractionQualityService.isValidEmail(email)) {
                        throw new BusinessValidationException("INVALID_EMAIL", "Invalid email format: " + email);
                    }
                }
            }

            if (candidate.getContact().getPhones() != null) {
                for (String phone : candidate.getContact().getPhones()) {
                    if (org.springframework.util.StringUtils.hasText(phone) && !com.apms.domain.ai.service.AiExtractionQualityService.isValidPhone(phone)) {
                        throw new BusinessValidationException("INVALID_PHONE", "Invalid phone format: " + phone);
                    }
                }
            }
        }
    }

    private void validateAiCandidateSubmission(CompanyCandidate candidate) {
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
    }

    private void validateManualFieldFormat(String fieldPath, Object value) {
        if (value == null) return;
        if ("business.foundedYear".equals(fieldPath)) {
            validateFoundedYear(value);
        } else if ("contact.website".equals(fieldPath)) {
            String str = String.valueOf(value);
            if (org.springframework.util.StringUtils.hasText(str) && !com.apms.domain.ai.service.AiExtractionQualityService.isValidUrl(str)) {
                throw new BusinessValidationException("INVALID_WEBSITE", "Invalid website URL format: " + str);
            }
        } else if ("contact.emails".equals(fieldPath)) {
            if (value instanceof java.util.Collection<?> list) {
                for (Object item : list) {
                    if (item != null && org.springframework.util.StringUtils.hasText(String.valueOf(item))
                            && !com.apms.domain.ai.service.AiExtractionQualityService.isValidEmail(String.valueOf(item))) {
                        throw new BusinessValidationException("INVALID_EMAIL", "Invalid email format: " + item);
                    }
                }
            } else if (value instanceof String str && org.springframework.util.StringUtils.hasText(str)
                    && !com.apms.domain.ai.service.AiExtractionQualityService.isValidEmail(str)) {
                throw new BusinessValidationException("INVALID_EMAIL", "Invalid email format: " + str);
            }
        } else if ("contact.phones".equals(fieldPath)) {
            if (value instanceof java.util.Collection<?> list) {
                for (Object item : list) {
                    if (item != null && org.springframework.util.StringUtils.hasText(String.valueOf(item))
                            && !com.apms.domain.ai.service.AiExtractionQualityService.isValidPhone(String.valueOf(item))) {
                        throw new BusinessValidationException("INVALID_PHONE", "Invalid phone format: " + item);
                    }
                }
            } else if (value instanceof String str && org.springframework.util.StringUtils.hasText(str)
                    && !com.apms.domain.ai.service.AiExtractionQualityService.isValidPhone(str)) {
                throw new BusinessValidationException("INVALID_PHONE", "Invalid phone format: " + str);
            }
        } else if ("contact.addresses".equals(fieldPath) || "contact.address".equals(fieldPath)) {
            if (value instanceof java.util.Collection<?> list) {
                for (Object item : list) {
                    if (item != null) {
                        String str = String.valueOf(item).trim();
                        if (str.length() > 500) {
                            throw new BusinessValidationException("INVALID_ADDRESS", "Address must not exceed 500 characters");
                        }
                    }
                }
            } else if (value instanceof String str && org.springframework.util.StringUtils.hasText(str)) {
                if (str.trim().length() > 500) {
                    throw new BusinessValidationException("INVALID_ADDRESS", "Address must not exceed 500 characters");
                }
            }
        }
    }

    public static void validateFoundedYear(Object value) {
        if (value == null) return;
        Integer year = null;
        if (value instanceof Number n) {
            year = n.intValue();
        } else if (value instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) return;
            try {
                year = Integer.parseInt(trimmed);
            } catch (NumberFormatException e) {
                throw new BusinessValidationException("INVALID_FOUNDED_YEAR", "Founded year must be an integer");
            }
        } else {
            throw new BusinessValidationException("INVALID_FOUNDED_YEAR", "Founded year must be an integer");
        }
        int currentYear = java.time.Year.now().getValue();
        if (year < 1800 || year > currentYear) {
            throw new BusinessValidationException("INVALID_FOUNDED_YEAR", "Founded year must be between 1800 and " + currentYear);
        }
    }

    private boolean isFieldValPresent(Object val) {
        if (val == null) return false;
        if (val instanceof String s) return org.springframework.util.StringUtils.hasText(s);
        if (val instanceof java.util.Collection<?> c) return !c.isEmpty();
        return true;
    }
}
