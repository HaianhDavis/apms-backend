package com.apms.domain.monitoring.service;

import com.apms.common.enums.MonitoringReviewResult;
import com.apms.common.enums.RelationshipType;
import com.apms.common.enums.RelationshipChangeStatus;
import com.apms.common.exception.BusinessConflictException;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.graph.service.GraphService;
import com.apms.domain.monitoring.dto.RelationshipChangeProposalRequest;
import com.apms.domain.monitoring.dto.RelationshipChangeProposalResponse;
import com.apms.domain.monitoring.dto.RelationshipChangeReviewRequest;
import com.apms.domain.monitoring.dto.RelationshipHistoryResponse;
import com.apms.domain.monitoring.model.CompanyMonitoringAssignment;
import com.apms.domain.monitoring.model.CompanyMonitoringReview;
import com.apms.domain.monitoring.model.CompanyRelationshipChangeProposal;
import com.apms.domain.monitoring.model.CompanyRelationshipHistory;
import com.apms.domain.monitoring.repository.CompanyMonitoringAssignmentRepository;
import com.apms.domain.monitoring.repository.CompanyMonitoringReviewRepository;
import com.apms.domain.monitoring.repository.CompanyRelationshipChangeProposalRepository;
import com.apms.domain.monitoring.repository.CompanyRelationshipHistoryRepository;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.profile.service.OwnerOrganizationService;
import com.apms.domain.profile.service.ProfileService;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyRelationshipService {

    private final CompanyRelationshipChangeProposalRepository proposalRepository;
    private final CompanyRelationshipHistoryRepository historyRepository;
    private final CompanyMonitoringAssignmentRepository assignmentRepository;
    private final CompanyMonitoringReviewRepository reviewRepository;
    private final CompanyProfileRepository profileRepository;
    private final GraphService graphService;
    private final OwnerOrganizationService ownerOrganizationService;
    private final AccountRepository accountRepository;
    private final CompanyMonitoringService companyMonitoringService;

    @Transactional
    public RelationshipChangeProposalResponse proposeRelationshipChange(Long assignmentId, RelationshipChangeProposalRequest request, Long currentUserId) {
        Account currentUser = accountRepository.findById(currentUserId)
                .orElseThrow(() -> new BusinessValidationException("Account not found"));

        CompanyMonitoringAssignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new BusinessValidationException("Assignment not found"));

        if (!assignment.getAssignedStaff().getId().equals(currentUser.getId())) {
            throw new AccessDeniedException("Only the assigned staff can propose relationship changes.");
        }

        CompanyProfile profile = profileRepository.findById(assignment.getCompanyProfileId())
                .orElseThrow(() -> new BusinessValidationException("Profile not found"));

        if (proposalRepository.existsByCompanyProfileIdAndStatus(profile.getId(), RelationshipChangeStatus.PENDING)) {
            throw new BusinessConflictException("A pending relationship change proposal already exists for this company.");
        }

        String ownerCompanyId = ownerOrganizationService.getOwnerCompanyId();
        String targetCompanyId = profile.getCompanyId();
        
        if (targetCompanyId == null) {
            throw new BusinessValidationException("Target company does not have a canonical companyId assigned yet.");
        }

        String currentRelTypeStr = graphService.getCurrentRelationshipType(ownerCompanyId, targetCompanyId);
        RelationshipType currentRelType = RelationshipType.valueOf(currentRelTypeStr);

        if (currentRelType == request.getNewRelationshipType()) {
            throw new BusinessValidationException("New relationship type must be different from the current relationship type.");
        }

        CompanyRelationshipChangeProposal proposal = CompanyRelationshipChangeProposal.builder()
                .companyProfileId(profile.getId())
                .monitoringAssignmentId(assignment.getId())
                .oldRelationshipType(currentRelType)
                .newRelationshipType(request.getNewRelationshipType())
                .reason(request.getReason())
                .effectiveAt(request.getEffectiveAt())
                .proposedByAccount(currentUser)
                .proposedAt(LocalDateTime.now())
                .status(RelationshipChangeStatus.PENDING)
                .build();

        proposal = proposalRepository.save(proposal);

        CompanyMonitoringReview review = CompanyMonitoringReview.builder()
                .assignment(assignment)
                .companyProfileId(profile.getId())
                .reviewedBy(currentUser)
                .reviewedAt(LocalDateTime.now())
                .result(MonitoringReviewResult.RELATIONSHIP_CHANGE_PROPOSED)
                .relationshipChangeProposalId(proposal.getId())
                .note(request.getReason())
                .build();

        reviewRepository.save(review);

        assignment.setLastReviewedAt(LocalDateTime.now());
        LocalDateTime nextReviewAt = companyMonitoringService.calculateNextReviewAt(LocalDateTime.now(), assignment.getFrequency());
        assignment.setNextReviewAt(nextReviewAt);
        assignmentRepository.save(assignment);

        return mapToResponse(proposal);
    }

    // No @Transactional on approve because we explicitly control graph vs SQL to avoid pseudo-transaction failures
    public RelationshipChangeProposalResponse approveProposal(Long proposalId, Long currentUserId) {
        Account currentUser = accountRepository.findById(currentUserId)
                .orElseThrow(() -> new BusinessValidationException("Account not found"));

        CompanyRelationshipChangeProposal proposal = proposalRepository.findById(proposalId)
                .orElseThrow(() -> new BusinessValidationException("Proposal not found"));

        if (proposal.getStatus() != RelationshipChangeStatus.PENDING) {
            throw new BusinessValidationException("Only PENDING proposals can be approved");
        }

        CompanyProfile profile = profileRepository.findById(proposal.getCompanyProfileId())
                .orElseThrow(() -> new BusinessValidationException("Profile not found"));
        
        companyMonitoringService.enforceResponsibleManager(profile, currentUser);

        String ownerCompanyId = ownerOrganizationService.getOwnerCompanyId();
        String targetCompanyId = profile.getCompanyId();

        String currentRelTypeStr = graphService.getCurrentRelationshipType(ownerCompanyId, targetCompanyId);
        RelationshipType currentRelType = RelationshipType.valueOf(currentRelTypeStr);

        if (currentRelType != proposal.getOldRelationshipType()) {
            throw new BusinessConflictException("Current relationship in graph does not match the old relationship of this proposal.");
        }

        CompanyRelationshipHistory history = CompanyRelationshipHistory.builder()
                .companyProfileId(profile.getId())
                .sourceCompanyId(ownerCompanyId)
                .targetCompanyId(targetCompanyId)
                .oldRelationshipType(proposal.getOldRelationshipType())
                .newRelationshipType(proposal.getNewRelationshipType())
                .reason(proposal.getReason())
                .effectiveAt(proposal.getEffectiveAt())
                .changedAt(LocalDateTime.now())
                .proposedByAccountId(proposal.getProposedByAccount().getId())
                .approvedByAccountId(currentUser.getId())
                .relationshipChangeProposalId(proposal.getId())
                .build();

        // 1. Update Graph
        graphService.updateRelationship(ownerCompanyId, targetCompanyId, proposal.getOldRelationshipType(), proposal.getNewRelationshipType());

        // 2. Persist SQL (best effort compensation if fails)
        try {
            proposal.setStatus(RelationshipChangeStatus.APPROVED);
            proposal.setReviewedByAccount(currentUser);
            proposal.setReviewedAt(LocalDateTime.now());
            
            saveApproval(proposal, history);
        } catch (Exception e) {
            log.error("Failed to persist SQL approval. Attempting to rollback Graph changes.", e);
            try {
                graphService.restoreRelationship(ownerCompanyId, targetCompanyId, proposal.getNewRelationshipType(), proposal.getOldRelationshipType());
            } catch (Exception rollbackEx) {
                log.error("CRITICAL CONSISTENCY ERROR: Failed to rollback Graph relationship after SQL persistence failed.", rollbackEx);
            }
            throw new RuntimeException("Failed to approve relationship change proposal due to persistence error", e);
        }

        return mapToResponse(proposal);
    }
    
    @Transactional
    protected void saveApproval(CompanyRelationshipChangeProposal proposal, CompanyRelationshipHistory history) {
        proposalRepository.save(proposal);
        historyRepository.save(history);
    }

    @Transactional
    public RelationshipChangeProposalResponse rejectProposal(Long proposalId, RelationshipChangeReviewRequest request, Long currentUserId) {
        Account currentUser = accountRepository.findById(currentUserId)
                .orElseThrow(() -> new BusinessValidationException("Account not found"));

        CompanyRelationshipChangeProposal proposal = proposalRepository.findById(proposalId)
                .orElseThrow(() -> new BusinessValidationException("Proposal not found"));

        if (proposal.getStatus() != RelationshipChangeStatus.PENDING) {
            throw new BusinessValidationException("Only PENDING proposals can be rejected");
        }

        CompanyProfile profile = profileRepository.findById(proposal.getCompanyProfileId())
                .orElseThrow(() -> new BusinessValidationException("Profile not found"));
        
        companyMonitoringService.enforceResponsibleManager(profile, currentUser);

        proposal.setStatus(RelationshipChangeStatus.REJECTED);
        proposal.setReviewedByAccount(currentUser);
        proposal.setReviewedAt(LocalDateTime.now());
        proposal.setRejectReason(request.getRejectReason());

        proposal = proposalRepository.save(proposal);
        return mapToResponse(proposal);
    }

    public List<RelationshipChangeProposalResponse> getPendingProposals(String companyProfileId, Long currentUserId) {
        Account currentUser = accountRepository.findById(currentUserId)
                .orElseThrow(() -> new BusinessValidationException("Account not found"));
        CompanyProfile profile = profileRepository.findById(companyProfileId)
                .orElseThrow(() -> new BusinessValidationException("Profile not found"));
        
        // Authorization: Responsible Manager or SYSTEM_ADMIN
        companyMonitoringService.enforceResponsibleManager(profile, currentUser);

        return proposalRepository.findByCompanyProfileIdAndStatus(companyProfileId, RelationshipChangeStatus.PENDING)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<RelationshipHistoryResponse> getRelationshipHistory(String companyProfileId) {
        return historyRepository.findByCompanyProfileIdOrderByChangedAtDesc(companyProfileId)
                .stream()
                .map(history -> {
                    Account proposedBy = accountRepository.findById(history.getProposedByAccountId()).orElse(null);
                    Account approvedBy = accountRepository.findById(history.getApprovedByAccountId()).orElse(null);
                    
                    return RelationshipHistoryResponse.builder()
                            .id(history.getId())
                            .companyProfileId(history.getCompanyProfileId())
                            .oldRelationshipType(history.getOldRelationshipType())
                            .newRelationshipType(history.getNewRelationshipType())
                            .reason(history.getReason())
                            .effectiveAt(history.getEffectiveAt())
                            .changedAt(history.getChangedAt())
                            .proposedByAccountId(proposedBy != null ? proposedBy.getId() : null)
                            .proposedByAccountName(proposedBy != null ? proposedBy.getEmail() : "SYSTEM")
                            .approvedByAccountId(approvedBy != null ? approvedBy.getId() : null)
                            .approvedByAccountName(approvedBy != null ? approvedBy.getEmail() : "SYSTEM")
                            .build();
                })
                .collect(Collectors.toList());
    }

    private RelationshipChangeProposalResponse mapToResponse(CompanyRelationshipChangeProposal proposal) {
        return RelationshipChangeProposalResponse.builder()
                .id(proposal.getId())
                .companyProfileId(proposal.getCompanyProfileId())
                .monitoringAssignmentId(proposal.getMonitoringAssignmentId())
                .oldRelationshipType(proposal.getOldRelationshipType())
                .newRelationshipType(proposal.getNewRelationshipType())
                .reason(proposal.getReason())
                .effectiveAt(proposal.getEffectiveAt())
                .proposedByAccountId(proposal.getProposedByAccount().getId())
                .proposedByAccountName(proposal.getProposedByAccount().getEmail())
                .proposedAt(proposal.getProposedAt())
                .status(proposal.getStatus())
                .reviewedByAccountId(proposal.getReviewedByAccount() != null ? proposal.getReviewedByAccount().getId() : null)
                .reviewedByAccountName(proposal.getReviewedByAccount() != null ? proposal.getReviewedByAccount().getEmail() : null)
                .reviewedAt(proposal.getReviewedAt())
                .rejectReason(proposal.getRejectReason())
                .build();
    }
}
