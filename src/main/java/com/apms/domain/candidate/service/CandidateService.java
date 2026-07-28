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
import com.apms.domain.document.repository.sql.ImportJobRepository;
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

        candidate.setRevisionNumber(candidate.getRevisionNumber() + 1);

        if (candidate.getMetadata() != null) {
            candidate.getMetadata().setLastModifiedBy(String.valueOf(userId));
            candidate.getMetadata().setUpdatedAt(LocalDateTime.now());
        }

        candidate = candidateRepository.save(candidate);
        log.info("Candidate updated manually: id={}, newRevision={}", candidateId, candidate.getRevisionNumber());

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

        // Prevent owner organization from being evaluated as a target
        if (candidate.getDeduplication() != null && ownerOrganizationService.isOwnerCompany(candidate.getDeduplication().getExistingProfileIdMatch())) {
            throw new BusinessValidationException("The Owner Organization cannot be selected as a project target.");
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
        Double confidenceScore = resolveCandidateConfidence(c);

        return CandidateResponse.builder()
                .id(c.getId())
                .projectId(c.getProjectId())
                .importJobId(c.getImportJobId())
                .rawDocumentId(c.getRawDocumentId())
                .candidateOrder(c.getCandidateOrder())
                .revisionNumber(c.getRevisionNumber())
                .status(c.getStatus())
                .suggestedRelationshipType(c.getSuggestedRelationshipType())
                .relationshipConfidenceScore(confidenceScore)
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
