package com.apms.domain.profile.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.SubmissionStatus;
import com.apms.common.enums.SystemRole;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.profile.CompanyProfileUpdateProposal;
import com.apms.domain.profile.dto.CompanyProfileUpdateProposalResponse;
import com.apms.domain.profile.dto.CreateCompanyProfileUpdateProposalRequest;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.profile.repository.mongo.CompanyProfileUpdateProposalRepository;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyProfileUpdateProposalService {

    private final CompanyProfileUpdateProposalRepository proposalRepository;
    private final CompanyProfileRepository companyProfileRepository;
    private final ProjectRepository projectRepository;
    private final AuditLogService auditLogService;

    @Transactional
    public CompanyProfileUpdateProposalResponse createProposal(Long projectId, Long taskId, CreateCompanyProfileUpdateProposalRequest request) {
        UserDetailsImpl currentUser = getCurrentUser();
        if (currentUser == null) {
            throw new AccessDeniedException("Unauthorized");
        }

        if (!hasRole(currentUser, SystemRole.SYSTEM_ADMIN) &&
            !projectRepository.existsByIdAndMembersAccountId(projectId, currentUser.getId())) {
            throw new AccessDeniedException("Must be a project member to submit proposals");
        }

        if (!companyProfileRepository.existsById(request.getCompanyProfileId())) {
            throw new ResourceNotFoundException("Target CompanyProfile does not exist");
        }

        CompanyProfileUpdateProposal proposal = CompanyProfileUpdateProposal.builder()
                .projectId(projectId)
                .taskId(taskId)
                .companyProfileId(request.getCompanyProfileId())
                .proposedIdentity(request.getProposedIdentity())
                .proposedBusiness(request.getProposedBusiness())
                .proposedContact(request.getProposedContact())
                .proposedInsights(request.getProposedInsights())
                .proposedFinancial(request.getProposedFinancial())
                .proposedMarket(request.getProposedMarket())
                .proposedInnovation(request.getProposedInnovation())
                .proposedRisk(request.getProposedRisk())
                .proposedCompliance(request.getProposedCompliance())
                .sourceDocumentIds(request.getSourceDocumentIds())
                .extractionId(request.getExtractionId())
                .changeSummary(request.getChangeSummary())
                .status(SubmissionStatus.DRAFT)
                .submittedBy(currentUser.getId())
                .build();

        proposal = proposalRepository.save(proposal);

        auditLogService.log(currentUser.getId(), AuditAction.PROFILE_UPDATE_PROPOSAL_CREATED, "CompanyProfileUpdateProposal", proposal.getId(), "Proposal created for profile: " + request.getCompanyProfileId());

        return toResponse(proposal);
    }

    @Transactional(readOnly = true)
    public CompanyProfileUpdateProposalResponse getProposal(String id) {
        CompanyProfileUpdateProposal proposal = proposalRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Proposal not found"));

        UserDetailsImpl currentUser = getCurrentUser();
        if (currentUser == null) {
            throw new AccessDeniedException("Unauthorized");
        }

        if (!hasRole(currentUser, SystemRole.SYSTEM_ADMIN) &&
            !projectRepository.existsByIdAndMembersAccountId(proposal.getProjectId(), currentUser.getId())) {
            throw new AccessDeniedException("Access denied to this proposal");
        }

        return toResponse(proposal);
    }

    private CompanyProfileUpdateProposalResponse toResponse(CompanyProfileUpdateProposal proposal) {
        return CompanyProfileUpdateProposalResponse.builder()
                .id(proposal.getId())
                .projectId(proposal.getProjectId())
                .taskId(proposal.getTaskId())
                .companyProfileId(proposal.getCompanyProfileId())
                .proposedIdentity(proposal.getProposedIdentity())
                .proposedBusiness(proposal.getProposedBusiness())
                .proposedContact(proposal.getProposedContact())
                .proposedInsights(proposal.getProposedInsights())
                .proposedFinancial(proposal.getProposedFinancial())
                .proposedMarket(proposal.getProposedMarket())
                .proposedInnovation(proposal.getProposedInnovation())
                .proposedRisk(proposal.getProposedRisk())
                .proposedCompliance(proposal.getProposedCompliance())
                .sourceDocumentIds(proposal.getSourceDocumentIds())
                .extractionId(proposal.getExtractionId())
                .status(proposal.getStatus())
                .submittedBy(proposal.getSubmittedBy())
                .reviewedBy(proposal.getReviewedBy())
                .reviewComment(proposal.getReviewComment())
                .changeSummary(proposal.getChangeSummary())
                .createdAt(proposal.getCreatedAt())
                .updatedAt(proposal.getUpdatedAt())
                .build();
    }

    private UserDetailsImpl getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserDetailsImpl) {
            return (UserDetailsImpl) auth.getPrincipal();
        }
        return null;
    }

    private boolean hasRole(UserDetailsImpl user, SystemRole role) {
        String roleName = "ROLE_" + role.name();
        return user.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(roleName));
    }
}
