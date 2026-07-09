package com.apms.domain.project.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.SubmissionStatus;
import com.apms.common.enums.SystemRole;
import com.apms.common.enums.TaskStatus;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.profile.CompanyProfileUpdateProposal;
import com.apms.domain.profile.repository.mongo.CompanyProfileUpdateProposalRepository;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.project.dto.CreateProjectTaskSubmissionRequest;
import com.apms.domain.project.dto.ProjectTaskSubmissionResponse;
import com.apms.domain.project.dto.ReviewTaskSubmissionRequest;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import com.apms.security.UserDetailsImpl;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectTaskSubmissionService {

    private final ProjectTaskSubmissionRepository submissionRepository;
    private final ProjectTaskRepository taskRepository;
    private final ProjectRepository projectRepository;
    private final AccountRepository accountRepository;
    private final CompanyProfileUpdateProposalRepository proposalRepository;
    private final com.apms.domain.profile.repository.mongo.CompanyProfileRepository companyProfileRepository;
    private final com.apms.domain.profile.repository.mongo.CompanyProfileVersionRepository versionRepository;
    private final AuditLogService auditLogService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Transactional
    public ProjectTaskSubmissionResponse submitTask(Long projectId, Long taskId, CreateProjectTaskSubmissionRequest request) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));
        ProjectTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found"));

        if (!task.getProject().getId().equals(projectId)) {
            throw new IllegalArgumentException("Task does not belong to project");
        }
        if (task.getStatus() == TaskStatus.DONE || task.getStatus() == TaskStatus.CANCELLED) {
            throw new IllegalStateException("Cannot submit work for a task that is DONE or CANCELLED");
        }

        List<ProjectTaskSubmission> existingSubmissions = submissionRepository.findByProjectTask_Id(taskId);
        boolean hasInReviewOrSubmitted = existingSubmissions.stream()
                .anyMatch(s -> s.getStatus() == SubmissionStatus.IN_REVIEW);
        if (hasInReviewOrSubmitted) {
            throw new com.apms.common.exception.BusinessValidationException("This task already has a draft under review.");
        }
        boolean hasApproved = existingSubmissions.stream()
                .anyMatch(s -> s.getStatus() == SubmissionStatus.APPROVED);
        if (hasApproved || task.getStatus() == TaskStatus.DONE) {
            throw new com.apms.common.exception.BusinessValidationException("This task already has an approved output.");
        }

        UserDetailsImpl currentUser = getCurrentUser();
        if (currentUser == null) throw new AccessDeniedException("Unauthorized");

        boolean isStaff = hasRole(currentUser, SystemRole.BUSINESS_DEVELOPMENT_STAFF);
        boolean isAdmin = hasRole(currentUser, SystemRole.SYSTEM_ADMIN);
        
        if (isStaff && !isAdmin) {
            if (task.getAssignedToAccount() == null || !task.getAssignedToAccount().getId().equals(currentUser.getId())) {
                throw new AccessDeniedException("Staff can only submit work for tasks assigned to them");
            }
        }

        Account submitter = accountRepository.findById(currentUser.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));

        ProjectTaskSubmission submission = ProjectTaskSubmission.builder()
                .projectTask(task)
                .project(project)
                .submittedByAccount(submitter)
                .submissionType(request.getSubmissionType())
                .targetEntityType(request.getTargetEntityType())
                .targetEntityId(request.getTargetEntityId())
                .status(SubmissionStatus.IN_REVIEW)
                .note(request.getNote())
                .submittedAt(LocalDateTime.now())
                .build();

        submission = submissionRepository.save(submission);

        // Update task status
        task.setStatus(TaskStatus.IN_REVIEW);
        task.setCompletedAt(null);
        taskRepository.save(task);

        // Update target entity if it's a proposal
        if (StringUtils.hasText(request.getTargetEntityId()) && "CompanyProfileUpdateProposal".equals(request.getTargetEntityType())) {
            proposalRepository.findById(request.getTargetEntityId()).ifPresent(proposal -> {
                proposal.setStatus(SubmissionStatus.IN_REVIEW);
                proposalRepository.save(proposal);
            });
        }

        auditLogService.log(currentUser.getId(), AuditAction.PROJECT_TASK_SUBMITTED, "ProjectTask", String.valueOf(task.getId()), "Task submitted for review");

        return toResponse(submission);
    }

    @Transactional(readOnly = true)
    public Page<ProjectTaskSubmissionResponse> getSubmissions(Long projectId, Long taskId, Pageable pageable) {
        UserDetailsImpl currentUser = getCurrentUser();
        if (currentUser == null) throw new AccessDeniedException("Unauthorized");
        
        Specification<ProjectTaskSubmission> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("project").get("id"), projectId));
            predicates.add(cb.equal(root.get("projectTask").get("id"), taskId));

            boolean isStaff = hasRole(currentUser, SystemRole.BUSINESS_DEVELOPMENT_STAFF);
            boolean isAdmin = hasRole(currentUser, SystemRole.SYSTEM_ADMIN);

            if (isStaff && !isAdmin) {
                // Staff can only view their own submissions or submissions on tasks assigned to them
                Predicate assignedToMe = cb.equal(root.get("projectTask").get("assignedToAccount").get("id"), currentUser.getId());
                Predicate submittedByMe = cb.equal(root.get("submittedByAccount").get("id"), currentUser.getId());
                predicates.add(cb.or(assignedToMe, submittedByMe));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return submissionRepository.findAll(spec, pageable).map(this::toResponse);
    }

    @Transactional
    public ProjectTaskSubmissionResponse reviewSubmission(Long projectId, Long taskId, Long submissionId, ReviewTaskSubmissionRequest request) {
        ProjectTaskSubmission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Submission not found"));

        if (!submission.getProject().getId().equals(projectId) || !submission.getProjectTask().getId().equals(taskId)) {
            throw new IllegalArgumentException("Submission does not belong to specified project/task");
        }

        UserDetailsImpl currentUser = getCurrentUser();
        if (currentUser == null) throw new AccessDeniedException("Unauthorized");

        Account reviewer = accountRepository.findById(currentUser.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));

        LocalDateTime now = LocalDateTime.now();
        ProjectTask task = submission.getProjectTask();

        submission.setReviewedByAccount(reviewer);
        submission.setReviewedAt(now);
        submission.setReviewComment(request.getComment());

        switch (request.getDecision()) {
            case APPROVE:
                submission.setStatus(SubmissionStatus.APPROVED);
                task.setStatus(TaskStatus.DONE);
                task.setCompletedAt(now);
                auditLogService.log(currentUser.getId(), AuditAction.PROJECT_TASK_SUBMISSION_APPROVED, "ProjectTaskSubmission", String.valueOf(submissionId), "Submission approved");
                
                // Handle proposal apply
                if (StringUtils.hasText(submission.getTargetEntityId()) && "CompanyProfileUpdateProposal".equals(submission.getTargetEntityType())) {
                    CompanyProfileUpdateProposal proposal = proposalRepository.findById(submission.getTargetEntityId())
                            .orElseThrow(() -> new ResourceNotFoundException("Proposal not found"));

                    if (proposal.getStatus() != SubmissionStatus.APPLIED && proposal.getCompanyProfileId() != null) {
                        com.apms.domain.profile.CompanyProfile profile = companyProfileRepository.findById(proposal.getCompanyProfileId())
                                .orElseThrow(() -> new ResourceNotFoundException("Target CompanyProfile not found"));

                        if (Boolean.TRUE.equals(profile.getIsDeleted())) {
                            throw new IllegalStateException("Cannot apply proposal to a deleted CompanyProfile");
                        }

                        // 1. Snapshot
                        @SuppressWarnings("unchecked")
                        java.util.Map<String, Object> snapshotMap = objectMapper.convertValue(profile, java.util.Map.class);
                        
                        com.apms.domain.profile.CompanyProfileVersion versionSnapshot = com.apms.domain.profile.CompanyProfileVersion.builder()
                                .companyProfileId(profile.getId())
                                .companyId(profile.getCompanyId())
                                .version(profile.getVersion())
                                .snapshot(snapshotMap)
                                .createdFromProposalId(proposal.getId())
                                .createdFromProjectId(proposal.getProjectId())
                                .createdFromTaskId(proposal.getTaskId())
                                .sourceDocumentIds(proposal.getSourceDocumentIds())
                                .changeSummary(proposal.getChangeSummary())
                                .createdBy(reviewer.getId())
                                .build();
                        
                        versionRepository.save(versionSnapshot);
                        auditLogService.log(currentUser.getId(), AuditAction.COMPANY_PROFILE_VERSION_CREATED, "CompanyProfileVersion", versionSnapshot.getId(), "Version snapshot created");

                        // 2. Partial Merge
                        if (proposal.getProposedIdentity() != null) {
                            profile.setIdentity(mergeSection(profile.getIdentity(), proposal.getProposedIdentity(), com.apms.domain.profile.CompanyProfile.Identity.class));
                        }
                        if (proposal.getProposedBusiness() != null) {
                            profile.setBusiness(mergeSection(profile.getBusiness(), proposal.getProposedBusiness(), com.apms.domain.profile.CompanyProfile.Business.class));
                        }
                        if (proposal.getProposedContact() != null) {
                            profile.setContact(mergeSection(profile.getContact(), proposal.getProposedContact(), com.apms.domain.profile.CompanyProfile.Contact.class));
                        }
                        if (proposal.getProposedInsights() != null) {
                            profile.setInsights(mergeSection(profile.getInsights(), proposal.getProposedInsights(), com.apms.domain.profile.CompanyProfile.Insights.class));
                        }

                        // 3. Append Source Documents
                        if (proposal.getSourceDocumentIds() != null && !proposal.getSourceDocumentIds().isEmpty()) {
                            if (profile.getSourceRefs() == null) {
                                profile.setSourceRefs(new com.apms.domain.profile.CompanyProfile.SourceRefs());
                            }
                            profile.getSourceRefs().getRawDocumentIds().addAll(proposal.getSourceDocumentIds());
                        }

                        // 4. Update Version and Metadata
                        profile.setVersion(profile.getVersion() == null ? 2 : profile.getVersion() + 1);
                        if (profile.getMetadata() == null) {
                            profile.setMetadata(new com.apms.domain.profile.CompanyProfile.Metadata());
                        }
                        profile.getMetadata().setLastModifiedBy(String.valueOf(reviewer.getId()));
                        profile.getMetadata().setUpdatedAt(now);

                        companyProfileRepository.save(profile);

                        // 5. Update proposal status
                        proposal.setStatus(SubmissionStatus.APPLIED);
                        proposal.setReviewedBy(reviewer.getId());
                        proposal.setReviewComment(request.getComment());
                        proposalRepository.save(proposal);
                        
                        auditLogService.log(currentUser.getId(), AuditAction.PROFILE_UPDATE_PROPOSAL_APPLIED, "CompanyProfileUpdateProposal", proposal.getId(), "Proposal applied and profile updated");
                    }
                }
                break;

            case REJECT:
                submission.setStatus(SubmissionStatus.REJECTED);
                task.setStatus(TaskStatus.IN_PROGRESS);
                task.setCompletedAt(null);
                auditLogService.log(currentUser.getId(), AuditAction.PROJECT_TASK_SUBMISSION_REJECTED, "ProjectTaskSubmission", String.valueOf(submissionId), "Submission rejected");
                if (StringUtils.hasText(submission.getTargetEntityId()) && "CompanyProfileUpdateProposal".equals(submission.getTargetEntityType())) {
                    proposalRepository.findById(submission.getTargetEntityId()).ifPresent(proposal -> {
                        proposal.setStatus(SubmissionStatus.REJECTED);
                        proposal.setReviewedBy(reviewer.getId());
                        proposal.setReviewComment(request.getComment());
                        proposalRepository.save(proposal);
                    });
                }
                break;
        }

        submissionRepository.save(submission);
        taskRepository.save(task);

        return toResponse(submission);
    }

    private ProjectTaskSubmissionResponse toResponse(ProjectTaskSubmission sub) {
        return ProjectTaskSubmissionResponse.builder()
                .id(sub.getId())
                .projectTaskId(sub.getProjectTask().getId())
                .projectId(sub.getProject().getId())
                .submittedByUserId(sub.getSubmittedByAccount().getId())
                .submissionType(sub.getSubmissionType())
                .targetEntityType(sub.getTargetEntityType())
                .targetEntityId(sub.getTargetEntityId())
                .status(sub.getStatus())
                .note(sub.getNote())
                .submittedAt(sub.getSubmittedAt())
                .reviewedByUserId(sub.getReviewedByAccount() != null ? sub.getReviewedByAccount().getId() : null)
                .reviewedAt(sub.getReviewedAt())
                .reviewComment(sub.getReviewComment())
                .createdAt(sub.getCreatedAt())
                .updatedAt(sub.getUpdatedAt())
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

    private <T> T mergeSection(T currentSection, java.util.Map<String, Object> proposedMap, Class<T> sectionClass) {
        if (proposedMap == null || proposedMap.isEmpty()) {
            return currentSection;
        }

        try {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> currentMap = currentSection != null 
                    ? objectMapper.convertValue(currentSection, java.util.Map.class) 
                    : new java.util.HashMap<>();

            for (java.util.Map.Entry<String, Object> entry : proposedMap.entrySet()) {
                if (entry.getValue() != null || currentMap.containsKey(entry.getKey())) {
                    // Overwrite scalar, list, or nested object. Null values from proposal are ignored per user MVP instructions unless explicitly needed (using simple overwrites here per instructions)
                    if (entry.getValue() != null || (entry.getValue() == null && !currentMap.containsKey(entry.getKey()))) {
                        // User instruction: "If a field is present with non-null value, update it. If a field is present with null value, ignore it for MVP and keep the existing value."
                        if (entry.getValue() != null) {
                            currentMap.put(entry.getKey(), entry.getValue());
                        }
                    }
                }
            }
            return objectMapper.convertValue(currentMap, sectionClass);
        } catch (Exception e) {
            log.error("Error merging section", e);
            return currentSection;
        }
    }
}
