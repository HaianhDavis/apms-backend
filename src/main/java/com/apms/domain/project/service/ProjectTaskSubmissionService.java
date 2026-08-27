package com.apms.domain.project.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.SubmissionStatus;
import com.apms.common.enums.SystemRole;
import com.apms.common.enums.TaskStatus;
import com.apms.common.enums.TaskType;
import com.apms.common.enums.ProjectStatus;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.profile.CompanyProfileUpdateProposal;
import com.apms.domain.profile.CompanyProfileVersion;
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
    private final List<ProjectTaskSubmissionApprovalHandler> approvalHandlers;
    private final com.apms.domain.notification.service.NotificationService notificationService;
    private final com.apms.domain.project.repository.sql.ProjectMemberRepository projectMemberRepository;

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.apms.domain.companymember.service.CompanyMemberResearchService companyMemberResearchService;

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.beans.factory.annotation.Qualifier("transactionManager")
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.apms.domain.candidate.service.CandidateService candidateService;

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.apms.domain.profile.service.CompanyProfileUpdateProposalService proposalService;

    @Transactional
    public ProjectTaskSubmissionResponse submitTask(Long projectId, Long taskId, CreateProjectTaskSubmissionRequest request) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));
        ProjectTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found"));

        if (project.getStatus() == ProjectStatus.CLOSED || project.getStatus() == ProjectStatus.COMPLETED) {
            throw new com.apms.common.exception.BusinessValidationException("Project is " + project.getStatus() + " and tasks cannot be modified.");
        }

        if (!task.getProject().getId().equals(projectId)) {
            throw new IllegalArgumentException("Task does not belong to project");
        }
        if (project.getStatus() == ProjectStatus.CLOSED || project.getStatus() == ProjectStatus.COMPLETED) {
            throw new com.apms.common.exception.BusinessValidationException("Project is " + project.getStatus() + " and tasks cannot be modified.");
        }
        if (task.getStatus() == TaskStatus.DONE || task.getStatus() == TaskStatus.CANCELLED || task.getStatus() == TaskStatus.AVAILABLE) {
            throw new IllegalStateException("Cannot submit work for a task that is DONE, CANCELLED, or AVAILABLE");
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

        LocalDateTime now = LocalDateTime.now();

        // 1. Ask the target entity to prepare its field approvals and return the submittedRevisionNumber
        Integer revision = null;
        if (StringUtils.hasText(request.getTargetEntityId())) {
            if ("CompanyCandidate".equals(request.getTargetEntityType())) {
                com.apms.domain.candidate.dto.CandidateResponse draft = candidateService.submitCandidate(request.getTargetEntityId(), submitter.getId());
                revision = draft.getRevisionNumber();
            } else if ("CompanyProfileUpdateProposal".equals(request.getTargetEntityType())) {
                com.apms.domain.profile.dto.CompanyProfileUpdateProposalResponse draft = proposalService.submitProposal(request.getTargetEntityId(), submitter.getId());
                revision = draft.getRevisionNumber();
            }
        }

        // 2. Idempotency Check: if this exact revision was already submitted, just return it
        if (revision != null) {
            final Integer finalRevision = revision;
            java.util.Optional<ProjectTaskSubmission> existingSub = submissionRepository.findByProjectTask_Id(taskId).stream()
                    .filter(s -> java.util.Objects.equals(s.getTargetEntityType(), request.getTargetEntityType())
                              && java.util.Objects.equals(s.getTargetEntityId(), request.getTargetEntityId())
                              && java.util.Objects.equals(s.getSubmittedRevisionNumber(), finalRevision))
                    .findFirst();
            if (existingSub.isPresent()) {
                // If it exists but the task is not IN_REVIEW, just sync the task status
                if (task.getStatus() != TaskStatus.IN_REVIEW) {
                    task.setStatus(TaskStatus.IN_REVIEW);
                    task.setCompletedAt(null);
                    taskRepository.saveAndFlush(task);
                }
                return toResponse(existingSub.get());
            }
        }

        // Ensure no other active IN_REVIEW submissions exist for the SAME task
        // But we already checked that at the top of the method.

        ProjectTaskSubmission submission = ProjectTaskSubmission.builder()
                .projectTask(task)
                .project(project)
                .submittedByAccount(submitter)
                .submissionType(request.getSubmissionType())
                .targetEntityType(request.getTargetEntityType())
                .targetEntityId(request.getTargetEntityId())
                .status(SubmissionStatus.IN_REVIEW)
                .note(request.getNote())
                .submittedAt(now)
                .submittedRevisionNumber(revision)
                .submittedAt(LocalDateTime.now())
                .build();

        ProjectTaskSubmission finalSubmission = submission;
        final Integer finalRevision = revision;
        try {
            org.springframework.transaction.support.TransactionTemplate template = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
            template.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            submission = template.execute(status -> submissionRepository.saveAndFlush(finalSubmission));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            log.warn("Concurrent duplicate submission detected for task {} and revision {}, fetching existing...", taskId, finalRevision);
            java.util.Optional<ProjectTaskSubmission> existingSub = submissionRepository.findByProjectTask_Id(taskId).stream()
                    .filter(s -> java.util.Objects.equals(s.getTargetEntityType(), request.getTargetEntityType())
                              && java.util.Objects.equals(s.getTargetEntityId(), request.getTargetEntityId())
                              && java.util.Objects.equals(s.getSubmittedRevisionNumber(), finalRevision))
                    .findFirst();
            if (existingSub.isPresent()) {
                if (task.getStatus() != TaskStatus.IN_REVIEW) {
                    task.setStatus(TaskStatus.IN_REVIEW);
                    task.setCompletedAt(null);
                    taskRepository.saveAndFlush(task);
                }
                return toResponse(existingSub.get());
            } else {
                throw e; // if we can't find it, rethrow
            }
        }

        // Update task status
        task.setStatus(TaskStatus.IN_REVIEW);
        task.setCompletedAt(null);
        taskRepository.saveAndFlush(task);

        // Target entity is already updated by the delegated submit call above.

        auditLogService.log(
                currentUser.getId(),
                AuditAction.PROJECT_TASK_SUBMITTED,
                "ProjectTask",
                String.valueOf(task.getId()),
                "Task submitted for review");

        // Notify managers
        Account sender = accountRepository.findById(currentUser.getId()).orElse(null);
        projectMemberRepository.findByProject_Id(projectId).stream()
                .filter(m -> m.getProjectRole() == com.apms.common.enums.ProjectRole.LEADER)
                .forEach(m -> notificationService.notifyTaskSubmitted(task, m.getAccount(), sender));

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

        if (submission.getProject().getStatus() == ProjectStatus.CLOSED || submission.getProject().getStatus() == ProjectStatus.COMPLETED) {
            throw new com.apms.common.exception.BusinessValidationException("Project is " + submission.getProject().getStatus() + " and tasks cannot be modified.");
        }

        UserDetailsImpl currentUser = getCurrentUser();
        if (currentUser == null) throw new AccessDeniedException("Unauthorized");

        if (!submission.getProject().getId().equals(projectId)) {
            throw new IllegalArgumentException("Submission does not belong to specified project");
        }
        if (!submission.getProjectTask().getId().equals(taskId)) {
            throw new IllegalArgumentException("Submission does not belong to specified task");
        }

        // === PROJECT-SCOPED REVIEW AUTHORIZATION ===
        ProjectTask task = submission.getProjectTask();

        // 1. Task must be IN_REVIEW
        if (task.getStatus() != TaskStatus.IN_REVIEW) {
            throw new com.apms.common.exception.BusinessValidationException("Task is not in review status.");
        }

        // 2. Self-review protection — applies to ALL reviewers including admin
        Long reviewerId = currentUser.getId();
        if (task.getAssignedToAccount() != null && reviewerId.equals(task.getAssignedToAccount().getId())) {
            throw new com.apms.common.exception.BusinessValidationException("You cannot review your own task submission.");
        }
        if (submission.getSubmittedByAccount() != null && reviewerId.equals(submission.getSubmittedByAccount().getId())) {
            throw new com.apms.common.exception.BusinessValidationException("You cannot review your own task submission.");
        }

        // 3. Reviewer must be an active project member with LEADER or DEPUTY role, unless SYSTEM_ADMIN
        boolean isSystemAdmin = hasRole(currentUser, com.apms.common.enums.SystemRole.SYSTEM_ADMIN);
        if (!isSystemAdmin) {
            Long actualProjectId = task.getProject().getId();
            java.util.Optional<com.apms.domain.project.ProjectMember> memberOpt =
                    projectMemberRepository.findByProject_IdAndAccount_Id(actualProjectId, reviewerId);
            if (memberOpt.isEmpty()) {
                throw new AccessDeniedException("You must be an active member of this project to review submissions.");
            }
            com.apms.common.enums.ProjectRole projectRole = memberOpt.get().getProjectRole();
            if (projectRole != com.apms.common.enums.ProjectRole.LEADER
                    && projectRole != com.apms.common.enums.ProjectRole.DEPUTY) {
                throw new AccessDeniedException("Only project Leaders and Deputies can review task submissions.");
            }
        }

        // 4. Submission must be the latest/current reviewable submission for this task
        java.util.List<ProjectTaskSubmission> taskSubmissions = submissionRepository.findByProjectTask_Id(taskId);
        ProjectTaskSubmission latestSubmission = taskSubmissions.stream()
                .max(java.util.Comparator.comparing(ProjectTaskSubmission::getId))
                .orElse(null);
        if (latestSubmission == null || !latestSubmission.getId().equals(submission.getId())) {
            throw new com.apms.common.exception.BusinessValidationException(
                    "This submission is not the current reviewable submission. A newer submission exists.");
        }

        if (submission.getProjectTask().getTaskType() == TaskType.COMPANY_DATA_PREPARATION) {
            validateManagerAuthorization(submission, currentUser, projectId, taskId);

            if ("CompanyCandidate".equals(submission.getTargetEntityType())) {
                candidateService.validateFinalReviewReadiness(submission.getTargetEntityId(), request.getDecision());
            } else if ("CompanyProfileUpdateProposal".equals(submission.getTargetEntityType())) {
                proposalService.validateFinalReviewReadiness(submission.getTargetEntityId(), request.getDecision());
            }
        }

        Account reviewer = accountRepository.findById(currentUser.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));

        LocalDateTime now = LocalDateTime.now();

        if (request.getDecision() == com.apms.common.enums.ReviewDecision.REJECT
                && isDocumentSubmission(submission)
                && !StringUtils.hasText(request.getComment())) {
            throw new com.apms.common.exception.BusinessValidationException("Reject reason is required for document submissions.");
        }

        submission.setReviewedByAccount(reviewer);
        submission.setReviewedAt(now);
        submission.setReviewComment(request.getComment());

        switch (request.getDecision()) {
            case APPROVE:
                submission.setStatus(SubmissionStatus.APPROVED);
                task.setStatus(TaskStatus.DONE);
                task.setCompletedAt(now);
                auditLogService.log(currentUser.getId(), AuditAction.PROJECT_TASK_SUBMISSION_APPROVED, "ProjectTaskSubmission", String.valueOf(submissionId), "Submission approved");
                
                // Notify assigned staff
                if (task.getAssignedToAccount() != null) {
                    notificationService.notifyTaskApproved(task, task.getAssignedToAccount(), reviewer);
                }

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

                        CompanyProfileVersion versionSnapshot = CompanyProfileVersion.builder()
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
                        if (proposal.getProposedFinancial() != null) {
                            profile.setFinancial(mergeSection(profile.getFinancial(), proposal.getProposedFinancial(), com.apms.domain.company.model.FinancialInfo.class));
                        }
                        if (proposal.getProposedMarket() != null) {
                            profile.setMarket(mergeSection(profile.getMarket(), proposal.getProposedMarket(), com.apms.domain.company.model.MarketInfo.class));
                        }
                        if (proposal.getProposedInnovation() != null) {
                            profile.setInnovation(mergeSection(profile.getInnovation(), proposal.getProposedInnovation(), com.apms.domain.company.model.InnovationInfo.class));
                        }
                        if (proposal.getProposedRisk() != null) {
                            profile.setRisk(mergeSection(profile.getRisk(), proposal.getProposedRisk(), com.apms.domain.company.model.RiskInfo.class));
                        }
                        if (proposal.getProposedCompliance() != null) {
                            profile.setCompliance(mergeSection(profile.getCompliance(), proposal.getProposedCompliance(), com.apms.domain.company.model.ComplianceInfo.class));
                        }

                        // 3. Append Source Documents
                        if (proposal.getSourceDocumentIds() != null && !proposal.getSourceDocumentIds().isEmpty()) {
                            if (profile.getSourceRefs() == null) {
                                profile.setSourceRefs(new com.apms.domain.profile.CompanyProfile.SourceRefs());
                            }
                            profile.getSourceRefs().getRawDocumentIds().addAll(proposal.getSourceDocumentIds());
                        }

                        // 4. Update Version and Metadata
                        profile.setVersion(incrementMinorVersion(profile.getVersion()));
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

                        log.info("Profile update proposal {} source documents remain as research/evidence only; they are not published to Company Profile documents.", proposal.getId());
                    }
                } else if (StringUtils.hasText(submission.getTargetEntityId()) && "CompanyCandidate".equals(submission.getTargetEntityType())) {
                    candidateService.approveCandidate(
                            submission.getTargetEntityId(),
                            new com.apms.domain.candidate.dto.ApproveCandidateRequest(),
                            reviewer.getId());
                } else if (submission.getSubmissionType() == com.apms.common.enums.SubmissionType.COMPANY_MEMBER_RESEARCH) {
                    companyMemberResearchService.handleApproval(submission, reviewer.getId(), request.getComment());
                } else {
                    for (ProjectTaskSubmissionApprovalHandler handler : approvalHandlers) {
                        if (handler.supports(submission.getSubmissionType())) {
                            handler.handleApproval(submission, reviewer.getId(), request.getComment());
                            break;
                        }
                    }
                }
                break;

            case REJECT:
                submission.setStatus(SubmissionStatus.CHANGES_REQUESTED);
                task.setStatus(TaskStatus.IN_PROGRESS);
                task.setCompletedAt(null);
                auditLogService.log(currentUser.getId(), AuditAction.PROJECT_TASK_SUBMISSION_REJECTED, "ProjectTaskSubmission", String.valueOf(submissionId), "Submission rejected");
                
                // Notify assigned staff
                if (task.getAssignedToAccount() != null) {
                    notificationService.notifyTaskChangesRequested(task, submission, task.getAssignedToAccount(), reviewer, request.getComment());
                }

                if (StringUtils.hasText(submission.getTargetEntityId()) && "CompanyProfileUpdateProposal".equals(submission.getTargetEntityType())) {
                    proposalRepository.findById(submission.getTargetEntityId()).ifPresent(proposal -> {
                        proposal.setStatus(SubmissionStatus.REJECTED);
                        proposal.setReviewedBy(reviewer.getId());
                        proposal.setReviewComment(request.getComment());
                        proposalRepository.save(proposal);
                    });
                } else if (StringUtils.hasText(submission.getTargetEntityId()) && "CompanyCandidate".equals(submission.getTargetEntityType())) {
                    candidateService.sendBackCandidate(submission.getTargetEntityId(), reviewer.getId());
                } else {
                    boolean handled = false;
                    for (ProjectTaskSubmissionApprovalHandler handler : approvalHandlers) {
                        if (handler.supports(submission.getSubmissionType())) {
                            handler.handleRejection(submission, reviewer.getId(), request.getComment());
                            handled = true;
                            break;
                        }
                    }
                    if (!handled && submission.getSubmissionType() == com.apms.common.enums.SubmissionType.DOCUMENT_COLLECTION) {
                        notifyDocumentCollectionRejected(submission, reviewer.getId(), request.getComment());
                    }
                }
                break;

            case REQUEST_REVISION:
                submission.setStatus(SubmissionStatus.REVISION_REQUESTED);
                task.setStatus(TaskStatus.IN_PROGRESS);
                task.setCompletedAt(null);
                auditLogService.log(currentUser.getId(), AuditAction.PROJECT_TASK_SUBMISSION_REVISION_REQUESTED, "ProjectTaskSubmission", String.valueOf(submissionId), "Revision requested");
                
                // Notify assigned staff
                if (task.getAssignedToAccount() != null) {
                    notificationService.notifyTaskChangesRequested(task, submission, task.getAssignedToAccount(), reviewer, request.getComment());
                }
                if (StringUtils.hasText(submission.getTargetEntityId()) && "CompanyProfileUpdateProposal".equals(submission.getTargetEntityType())) {
                    proposalRepository.findById(submission.getTargetEntityId()).ifPresent(proposal -> {
                        proposal.setStatus(SubmissionStatus.REVISION_REQUESTED);
                        proposal.setReviewedBy(reviewer.getId());
                        proposal.setReviewComment(request.getComment());
                        proposalRepository.save(proposal);
                    });
                }
                break;
        }

        submissionRepository.save(submission);
        taskRepository.save(task);

        if (request.getDecision() == com.apms.common.enums.ReviewDecision.REJECT || request.getDecision() == com.apms.common.enums.ReviewDecision.REQUEST_REVISION) {
            Account recipient = submission.getSubmittedByAccount() != null ? submission.getSubmittedByAccount() : task.getAssignedToAccount();
            if (recipient != null) {
                notificationService.notifyTaskChangesRequested(task, submission, recipient, reviewer, request.getComment());
            }
        }

        return toResponse(submission);
    }

    private boolean isDocumentSubmission(ProjectTaskSubmission submission) {
        return submission.getSubmissionType() == com.apms.common.enums.SubmissionType.DOCUMENT_COLLECTION
                || submission.getSubmissionType() == com.apms.common.enums.SubmissionType.PARTNER_CONTRACT_COLLECTION;
    }

    private void notifyDocumentCollectionRejected(ProjectTaskSubmission submission, Long reviewerId, String comment) {
        notificationService.notifyDocumentRejected(submission, null, "Document package", reviewerId, comment);
    }

    @Transactional
    public void reviewFields(Long projectId, Long taskId, Long submissionId, com.apms.domain.project.dto.FieldReviewRequest request) {
        ProjectTaskSubmission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Submission not found"));
        
        if (submission.getProject().getStatus() == ProjectStatus.CLOSED || submission.getProject().getStatus() == ProjectStatus.COMPLETED) {
            throw new com.apms.common.exception.BusinessValidationException("Project is " + submission.getProject().getStatus() + " and tasks cannot be modified.");
        }

        UserDetailsImpl currentUser = getCurrentUser();
        if (currentUser == null) throw new AccessDeniedException("Unauthorized");

        validateManagerAuthorization(submission, currentUser, projectId, taskId);

        bindLegacyRevisionIfNecessary(submission, request.getExpectedRevisionNumber());

        if ("CompanyCandidate".equals(submission.getTargetEntityType())) {
            candidateService.reviewFields(submission.getTargetEntityId(), request, currentUser.getId());
        } else if ("CompanyProfileUpdateProposal".equals(submission.getTargetEntityType())) {
            proposalService.reviewFields(submission.getTargetEntityId(), request, currentUser.getId());
        } else {
            throw new com.apms.common.exception.BusinessValidationException("Field review not supported for this submission type");
        }
    }

    @Transactional
    public void reopenField(Long projectId, Long taskId, Long submissionId, String fieldPath, com.apms.domain.project.dto.FieldReopenRequest request) {
        ProjectTaskSubmission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Submission not found"));

        if (submission.getProject().getStatus() == ProjectStatus.CLOSED || submission.getProject().getStatus() == ProjectStatus.COMPLETED) {
            throw new com.apms.common.exception.BusinessValidationException("Project is " + submission.getProject().getStatus() + " and tasks cannot be modified.");
        }

        UserDetailsImpl currentUser = getCurrentUser();
        if (currentUser == null) throw new AccessDeniedException("Unauthorized");

        validateManagerAuthorization(submission, currentUser, projectId, taskId);

        bindLegacyRevisionIfNecessary(submission, request.getExpectedRevisionNumber());

        if ("CompanyCandidate".equals(submission.getTargetEntityType())) {
            candidateService.reopenField(submission.getTargetEntityId(), fieldPath, request, currentUser.getId());
        } else if ("CompanyProfileUpdateProposal".equals(submission.getTargetEntityType())) {
            proposalService.reopenField(submission.getTargetEntityId(), fieldPath, request, currentUser.getId());
        } else {
            throw new com.apms.common.exception.BusinessValidationException("Field reopen not supported for this submission type");
        }
    }

    @Transactional(readOnly = true)
    public com.apms.domain.project.dto.ReviewSummaryResponse getReviewSummary(Long projectId, Long taskId, Long submissionId) {
        ProjectTaskSubmission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Submission not found"));

        if (!submission.getProject().getId().equals(projectId) || !submission.getProjectTask().getId().equals(taskId)) {
            throw new IllegalArgumentException("Submission does not belong to specified project/task");
        }

        UserDetailsImpl currentUser = getCurrentUser();
        if (currentUser == null) throw new AccessDeniedException("Unauthorized");

        boolean isSystemAdmin = hasRole(currentUser, com.apms.common.enums.SystemRole.SYSTEM_ADMIN);
        if (!isSystemAdmin) {
            java.util.Optional<com.apms.domain.project.ProjectMember> memberOpt =
                    projectMemberRepository.findByProject_IdAndAccount_Id(projectId, currentUser.getId());
            if (memberOpt.isEmpty()) {
                throw new AccessDeniedException("You must be an active member of this project.");
            }
            com.apms.common.enums.ProjectRole role = memberOpt.get().getProjectRole();
            boolean isStaff = hasRole(currentUser, com.apms.common.enums.SystemRole.BUSINESS_DEVELOPMENT_STAFF);

            if (role != com.apms.common.enums.ProjectRole.LEADER && role != com.apms.common.enums.ProjectRole.DEPUTY) {
                // If not a reviewer, they must be Staff reading their own task's feedback
                if (!isStaff) {
                    throw new AccessDeniedException("You do not have permission to view this review summary.");
                }
                boolean assignedToMe = submission.getProjectTask().getAssignedToAccount() != null && 
                                       submission.getProjectTask().getAssignedToAccount().getId().equals(currentUser.getId());
                boolean submittedByMe = submission.getSubmittedByAccount() != null && 
                                        submission.getSubmittedByAccount().getId().equals(currentUser.getId());
                if (!assignedToMe && !submittedByMe) {
                    throw new AccessDeniedException("You can only read feedback for your own tasks/submissions.");
                }
            }
        }

        if ("CompanyCandidate".equals(submission.getTargetEntityType())) {
            return candidateService.getReviewSummary(submission.getTargetEntityId(), submission.getSubmittedRevisionNumber());
        } else if ("CompanyProfileUpdateProposal".equals(submission.getTargetEntityType())) {
            return proposalService.getReviewSummary(submission.getTargetEntityId(), submission.getSubmittedRevisionNumber());
        } else {
            throw new com.apms.common.exception.BusinessValidationException("Review summary not supported for this submission type");
        }
    }

    private void bindLegacyRevisionIfNecessary(ProjectTaskSubmission submission, Integer expectedRevisionNumber) {
        if (submission.getSubmittedRevisionNumber() == null) {
            if (submission.getStatus() == SubmissionStatus.IN_REVIEW && expectedRevisionNumber != null) {
                submission.setSubmittedRevisionNumber(expectedRevisionNumber);
                submissionRepository.save(submission);
            } else {
                throw new com.apms.common.exception.BusinessValidationException("Cannot review a legacy submission that is not IN_REVIEW or without an expected revision");
            }
        }
    }

    private void validateManagerAuthorization(ProjectTaskSubmission submission, UserDetailsImpl currentUser, Long projectId, Long taskId) {
        // Check if user has global MANAGER role OR is a project LEADER/DEPUTY
        boolean isGlobalManager = hasRole(currentUser, SystemRole.BUSINESS_DEVELOPMENT_MANAGER);
        boolean isProjectReviewer = false;
        java.util.Optional<com.apms.domain.project.ProjectMember> memberOpt =
                projectMemberRepository.findByProject_IdAndAccount_Id(projectId, currentUser.getId());
        if (memberOpt.isPresent()) {
            com.apms.common.enums.ProjectRole role = memberOpt.get().getProjectRole();
            isProjectReviewer = role == com.apms.common.enums.ProjectRole.LEADER
                    || role == com.apms.common.enums.ProjectRole.DEPUTY;
        }
        if (!isGlobalManager && !isProjectReviewer) {
            throw new AccessDeniedException("Only project Leaders, Deputies, or Managers can perform this action");
        }
        if (!submission.getProject().getId().equals(projectId)) {
            throw new IllegalArgumentException("Submission does not belong to specified project");
        }
        if (!submission.getProjectTask().getId().equals(taskId)) {
            throw new IllegalArgumentException("Submission does not belong to specified task");
        }
        if (submission.getProjectTask().getTaskType() != com.apms.common.enums.TaskType.COMPANY_DATA_PREPARATION) {
            throw new com.apms.common.exception.BusinessValidationException("Task type must be COMPANY_DATA_PREPARATION for field approval");
        }
        if (submission.getTargetEntityType() == null || submission.getTargetEntityId() == null) {
            throw new com.apms.common.exception.BusinessValidationException("Submission target entity is missing");
        }
        if (!projectRepository.existsByIdAndMembersAccountId(projectId, currentUser.getId())) {
            throw new AccessDeniedException("Reviewer must be a member of the project");
        }
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
    private String incrementMinorVersion(String currentVersion) {
        if (currentVersion == null || currentVersion.isEmpty()) return "1.1";
        try {
            String[] parts = currentVersion.split("\\.");
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return major + "." + (minor + 1);
        } catch (Exception e) {
            return currentVersion + ".1";
        }
    }
}
