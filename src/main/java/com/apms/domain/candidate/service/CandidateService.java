package com.apms.domain.candidate.service;

import com.apms.common.enums.CandidateStatus;
import com.apms.common.enums.RelationshipType;
import com.apms.common.event.CandidateApprovedEvent;
import com.apms.common.exception.BusinessValidationException;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.ai.dto.AiExtractionResult;
import com.apms.domain.ai.dto.ExtractedCompanyData;
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
import com.apms.domain.document.repository.sql.ImportJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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

    // ─────────────────────────────────────────────
    // CREATE (from AI)
    // ─────────────────────────────────────────────

    @Transactional
    public CandidateResponse createFromAi(Long importJobId, Long creatorId) {
        ImportJob importJob = importJobRepository.findById(importJobId)
                .orElseThrow(() -> new ResourceNotFoundException("ImportJob not found: " + importJobId));

        // 1. Load extraction from cache (set by /ai/extract), or run if not yet cached
        AiExtractionResult aiResult = aiExtractionService.getOrExtractCompanyData(importJobId);
        
        return buildAndSaveCandidate(String.valueOf(importJob.getProjectId()), String.valueOf(importJobId), importJob.getRawDocumentId(), aiResult.getExtractedData(), creatorId);
    }

    @Transactional
    public CandidateResponse createFromExtractionId(String extractionId, Long creatorId) {
        com.apms.domain.ai.AiExtractionCache cache = aiExtractionService.getExtractionById(extractionId);
        
        ImportJob importJob = importJobRepository.findById(cache.getImportJobId())
                .orElseThrow(() -> new ResourceNotFoundException("ImportJob not found: " + cache.getImportJobId()));

        return buildAndSaveCandidate(String.valueOf(importJob.getProjectId()), String.valueOf(importJob.getId()), importJob.getRawDocumentId(), cache.getExtractedData(), creatorId);
    }

    private CandidateResponse buildAndSaveCandidate(String projectId, String importJobId, String rawDocumentId, ExtractedCompanyData extractedData, Long creatorId) {
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
                .build();

        CompanyCandidate.Contact contact = CompanyCandidate.Contact.builder()
                .website(extractedData.getWebsite())
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
                .financial(extractedData.getFinancial())
                .market(extractedData.getMarket())
                .innovation(extractedData.getInnovation())
                .risk(extractedData.getRisk())
                .compliance(extractedData.getCompliance())
                .lifecycle(lifecycle)
                .metadata(metadata)
                .build();

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
    public CandidateResponse updateCandidate(String candidateId, UpdateCandidateRequest request, Long userId) {
        CompanyCandidate candidate = findCandidateOrThrow(candidateId);

        if (candidate.getStatus() == CandidateStatus.APPROVED || candidate.getStatus() == CandidateStatus.REJECTED) {
            throw new BusinessValidationException("Cannot edit candidate in status: " + candidate.getStatus());
        }

        if (request.getIdentity() != null) candidate.setIdentity(request.getIdentity());
        if (request.getBusiness() != null) candidate.setBusiness(request.getBusiness());
        if (request.getCompanySize() != null) candidate.setCompanySize(request.getCompanySize());
        if (request.getContact() != null) candidate.setContact(request.getContact());
        if (request.getInsights() != null) candidate.setInsights(request.getInsights());

        if (request.getSuggestedRelationshipType() != null) {
            candidate.setSuggestedRelationshipType(request.getSuggestedRelationshipType());
        }

        candidate.setRevisionNumber(candidate.getRevisionNumber() + 1);
        
        if (candidate.getMetadata() != null) {
            candidate.getMetadata().setLastModifiedBy(String.valueOf(userId));
            candidate.getMetadata().setUpdatedAt(LocalDateTime.now());
        }

        candidate = candidateRepository.save(candidate);
        log.info("Candidate updated manually: id={}, newRevision={}", candidateId, candidate.getRevisionNumber());
        
        return toResponse(candidate);
    }

    @Transactional
    public CandidateResponse submitCandidate(String candidateId) {
        CompanyCandidate candidate = findCandidateOrThrow(candidateId);

        if (candidate.getStatus() != CandidateStatus.DRAFT && candidate.getStatus() != CandidateStatus.CORRECTED) {
            throw new BusinessValidationException("Only DRAFT or CORRECTED candidates can be submitted");
        }

        candidate.setStatus(CandidateStatus.PENDING_REVIEW);
        if (candidate.getMetadata() != null) {
            candidate.getMetadata().setUpdatedAt(LocalDateTime.now());
        }

        candidate = candidateRepository.save(candidate);
        log.info("Candidate submitted for review: id={}", candidateId);
        return toResponse(candidate);
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

        if (candidate.getStatus() != CandidateStatus.PENDING_REVIEW) {
            throw new BusinessValidationException("Only PENDING_REVIEW candidates can be approved");
        }

        candidate.setStatus(CandidateStatus.APPROVED);
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

        log.info("Candidate approved: id={}, finalType={}", candidateId, finalType);
        return toResponse(candidate);
    }

    // ─────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────

    private CompanyCandidate findCandidateOrThrow(String candidateId) {
        return candidateRepository.findById(candidateId)
                .orElseThrow(() -> new ResourceNotFoundException("Candidate not found: " + candidateId));
    }

    private CandidateResponse toResponse(CompanyCandidate c) {
        return CandidateResponse.builder()
                .id(c.getId())
                .projectId(c.getProjectId())
                .importJobId(c.getImportJobId())
                .rawDocumentId(c.getRawDocumentId())
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
                .scorePreview(c.getScorePreview())
                .aiMetadata(c.getAiMetadata())
                .metadata(c.getMetadata())
                .build();
    }
}
