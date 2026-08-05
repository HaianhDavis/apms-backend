package com.apms.domain.project.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.CandidateStatus;
import com.apms.common.enums.SystemRole;
import com.apms.common.enums.TaskStatus;
import com.apms.common.enums.TaskType;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.ai.AiExtractionCache;
import com.apms.domain.ai.dto.ExtractionQualityStatus;
import com.apms.domain.ai.repository.mongo.AiExtractionCacheRepository;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.dto.CreateProjectTaskRequest;
import com.apms.domain.project.dto.ProjectTaskResponse;
import com.apms.domain.project.dto.UpdateProjectTaskRequest;
import com.apms.domain.project.dto.ProjectTaskWorkbenchResponse;
import com.apms.domain.project.dto.ProjectTaskSubmissionResponse;
import com.apms.domain.project.dto.WorkbenchDocumentResponse;
import com.apms.domain.project.dto.CandidateDraftSummary;
import com.apms.domain.project.dto.ProposalDraftSummary;
import com.apms.common.enums.TaskAction;
import com.apms.domain.document.service.DocumentService;
import com.apms.domain.document.dto.ImportJobResponse;
import com.apms.domain.candidate.repository.mongo.CompanyCandidateRepository;
import com.apms.domain.profile.repository.mongo.CompanyProfileUpdateProposalRepository;
import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.candidate.CompanyCandidate;
import com.apms.domain.profile.CompanyProfileUpdateProposal;
import com.apms.common.enums.ProjectType;
import com.apms.common.enums.SubmissionStatus;
import com.apms.common.enums.SubmissionType;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.enums.RoleEvaluationStatus;
import com.apms.domain.score.repository.mongo.RoleEvaluationDraftRepository;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import com.apms.domain.notification.service.NotificationService;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectTaskService {

    private final ProjectTaskRepository projectTaskRepository;
    private final ProjectRepository projectRepository;
    private final AccountRepository accountRepository;
    private final AuditLogService auditLogService;
    private final DocumentService documentService;
    private final AiExtractionCacheRepository extractionCacheRepository;
    private final CompanyCandidateRepository candidateRepository;
    private final CompanyProfileUpdateProposalRepository proposalRepository;
    private final ProjectTaskSubmissionRepository submissionRepository;
    private final RoleEvaluationDraftRepository roleEvaluationDraftRepository;
    private final NotificationService notificationService;

    @Transactional
    public ProjectTaskResponse createTask(Long projectId, CreateProjectTaskRequest request) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));

        UserDetailsImpl currentUser = getCurrentUser();
        if (currentUser == null) {
            throw new AccessDeniedException("Unauthorized");
        }

        Account createdBy = accountRepository.findById(currentUser.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));

        Account assignedTo = null;
        if (request.getAssignedToUserId() != null) {
            if (!projectRepository.existsByIdAndMembersAccountId(projectId, request.getAssignedToUserId())) {
                throw new IllegalArgumentException("Assigned user must be a project member");
            }
            assignedTo = accountRepository.findById(request.getAssignedToUserId())
                    .orElseThrow(() -> new ResourceNotFoundException("Assigned account not found"));
        }

        ProjectTask task = ProjectTask.builder()
                .project(project)
                .title(request.getTitle())
                .description(request.getDescription())
                .assignedToAccount(assignedTo)
                .priority(request.getPriority())
                .dueDate(request.getDueDate())
                .createdByAccount(createdBy)
                .status(TaskStatus.TODO)
                .taskType(request.getTaskType() != null ? request.getTaskType() : TaskType.GENERAL_TASK)
                .build();

        task = projectTaskRepository.save(task);

        auditLogService.log(currentUser.getId(), AuditAction.PROJECT_TASK_CREATED, "ProjectTask", String.valueOf(task.getId()), "Task created");
        if (assignedTo != null) {
            auditLogService.log(currentUser.getId(), AuditAction.PROJECT_TASK_ASSIGNED, "ProjectTask", String.valueOf(task.getId()), "Task assigned to user: " + assignedTo.getId());
            notificationService.notifyTaskAssigned(task, assignedTo, createdBy);
        }

        return toResponse(task);
    }

    @Transactional(readOnly = true)
    public Page<ProjectTaskResponse> getTasks(Long projectId, TaskStatus status, Long assignedToUserId, Pageable pageable) {
        return getTasks(projectId, status, assignedToUserId, pageable, null, false);
    }

    @Transactional(readOnly = true)
    public Page<ProjectTaskResponse> getTasks(Long projectId, TaskStatus status, Long assignedToUserId, Pageable pageable, Long currentUserId, boolean restrictToAssignedUser) {
        Long effectiveAssignedToUserId = restrictToAssignedUser ? currentUserId : assignedToUserId;
        Specification<ProjectTask> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("project").get("id"), projectId));

            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (effectiveAssignedToUserId != null) {
                predicates.add(cb.equal(root.get("assignedToAccount").get("id"), effectiveAssignedToUserId));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return projectTaskRepository.findAll(spec, pageable).map(this::toResponse);
    }

    @Transactional
    public ProjectTaskResponse updateTask(Long projectId, Long taskId, UpdateProjectTaskRequest request) {
        ProjectTask task = projectTaskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found"));

        if (!task.getProject().getId().equals(projectId)) {
            throw new IllegalArgumentException("Task does not belong to the specified project");
        }

        UserDetailsImpl currentUser = getCurrentUser();
        if (currentUser == null) {
            throw new AccessDeniedException("Unauthorized");
        }

        boolean isStaff = hasRole(currentUser, SystemRole.BUSINESS_DEVELOPMENT_STAFF);
        boolean isAdminOrManager = hasRole(currentUser, SystemRole.SYSTEM_ADMIN) || hasRole(currentUser, SystemRole.BUSINESS_DEVELOPMENT_MANAGER);

        if (isStaff && !isAdminOrManager) {
            if (task.getAssignedToAccount() == null || !task.getAssignedToAccount().getId().equals(currentUser.getId())) {
                throw new AccessDeniedException("Staff can only update tasks assigned to them");
            }
            // Staff can only update status
            if (request.getTitle() != null && !request.getTitle().equals(task.getTitle())) {
                throw new AccessDeniedException("Staff cannot update title");
            }
            if (request.getDescription() != null && !request.getDescription().equals(task.getDescription())) {
                throw new AccessDeniedException("Staff cannot update description");
            }
            if (request.getPriority() != null && request.getPriority() != task.getPriority()) {
                throw new AccessDeniedException("Staff cannot update priority");
            }
            if (request.getDueDate() != null && !request.getDueDate().equals(task.getDueDate())) {
                throw new AccessDeniedException("Staff cannot update due date");
            }
            if (request.getAssignedToUserId() != null && !request.getAssignedToUserId().equals(task.getAssignedToAccount().getId())) {
                throw new AccessDeniedException("Staff cannot reassign tasks");
            }
        }

        boolean statusChanged = false;
        if (request.getStatus() != null && request.getStatus() != task.getStatus()) {
            if (request.getStatus() == TaskStatus.DONE) {
                task.setCompletedAt(LocalDateTime.now());
            } else if (task.getStatus() == TaskStatus.DONE) {
                task.setCompletedAt(null);
            }
            task.setStatus(request.getStatus());
            statusChanged = true;
        }

        Account newlyAssignedTo = null;

        if (!isStaff || isAdminOrManager) {
            if (request.getTitle() != null) task.setTitle(request.getTitle());
            if (request.getDescription() != null) task.setDescription(request.getDescription());
            if (request.getPriority() != null) task.setPriority(request.getPriority());
            if (request.getDueDate() != null) task.setDueDate(request.getDueDate());
            if (request.getTaskType() != null && task.getStatus() != TaskStatus.DONE && task.getStatus() != TaskStatus.CANCELLED) {
                task.setTaskType(request.getTaskType());
            }

            if (request.getAssignedToUserId() != null) {
                Long currentAssignedId = task.getAssignedToAccount() != null ? task.getAssignedToAccount().getId() : null;
                if (!request.getAssignedToUserId().equals(currentAssignedId)) {
                    if (!projectRepository.existsByIdAndMembersAccountId(projectId, request.getAssignedToUserId())) {
                        throw new IllegalArgumentException("Assigned user must be a project member");
                    }
                    Account newAssignedTo = accountRepository.findById(request.getAssignedToUserId())
                            .orElseThrow(() -> new ResourceNotFoundException("Assigned account not found"));
                    task.setAssignedToAccount(newAssignedTo);
                    newlyAssignedTo = newAssignedTo;
                    auditLogService.log(currentUser.getId(), AuditAction.PROJECT_TASK_ASSIGNED, "ProjectTask", String.valueOf(task.getId()), "Task reassigned to user: " + newAssignedTo.getId());
                }
            }
        }

        task = projectTaskRepository.save(task);

        auditLogService.log(currentUser.getId(), AuditAction.PROJECT_TASK_UPDATED, "ProjectTask", String.valueOf(task.getId()), "Task details updated");
        if (statusChanged) {
            auditLogService.log(currentUser.getId(), AuditAction.PROJECT_TASK_STATUS_CHANGED, "ProjectTask", String.valueOf(task.getId()), "Task status changed to " + task.getStatus());
        }
        if (newlyAssignedTo != null) {
            Account sender = accountRepository.findById(currentUser.getId()).orElse(null);
            notificationService.notifyTaskAssigned(task, newlyAssignedTo, sender);
        }

        return toResponse(task);
    }

    @Transactional
    public void deleteTask(Long projectId, Long taskId) {
        ProjectTask task = projectTaskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found"));

        if (!task.getProject().getId().equals(projectId)) {
            throw new IllegalArgumentException("Task does not belong to the specified project");
        }

        UserDetailsImpl currentUser = getCurrentUser();
        if (currentUser == null) {
            throw new AccessDeniedException("Unauthorized");
        }

        List<ProjectTaskSubmission> submissions = submissionRepository.findByProjectTask_Id(taskId);
        if (!submissions.isEmpty()) {
            submissionRepository.deleteAll(submissions);
        }

        projectTaskRepository.delete(task);
        auditLogService.log(currentUser.getId(), AuditAction.PROJECT_TASK_UPDATED, "ProjectTask", String.valueOf(taskId), "Task deleted");
    }

    @Transactional
    public ProjectTaskWorkbenchResponse getTaskWorkbench(Long projectId, Long taskId) {
        ProjectTask task = projectTaskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found"));

        if (!task.getProject().getId().equals(projectId)) {
            throw new IllegalArgumentException("Task does not belong to the specified project");
        }

        UserDetailsImpl currentUser = getCurrentUser();
        if (currentUser == null) {
            throw new AccessDeniedException("Unauthorized");
        }

        Project project = task.getProject();
        TaskType tType = task.getTaskType() != null ? task.getTaskType() : TaskType.GENERAL_TASK;
        if (tType == TaskType.ROLE_EVALUATION) {
            syncSubmittedRoleEvaluationState(projectId, task, currentUser);
        }
        
        // 1. Evaluate actions
        List<TaskAction> actions = evaluateAvailableActions(currentUser, task, project, tType);

        // 2. Fetch documents (only metadata/import jobs)
        List<ImportJobResponse> rawDocuments = documentService.getTaskImportJobs(projectId, taskId);
        
        List<WorkbenchDocumentResponse> documents = new ArrayList<>();
        for (ImportJobResponse doc : rawDocuments) {
            AiExtractionCache extraction = extractionCacheRepository.findTopByImportJobIdOrderByCreatedAtDesc(doc.getId()).orElse(null);
            
            String latestExtractionId = null;
            ExtractionQualityStatus status = null;
            Double evidenceCoverageRate = null;
            Double completenessRate = null;
            Integer warningFields = null;
            Integer failedFields = null;
            boolean canGenerateDraft = false;
            
            if (extraction != null) {
                latestExtractionId = extraction.getId();
                status = extraction.getQualityStatus();
                
                if (extraction.getQualityMetrics() != null) {
                    evidenceCoverageRate = extraction.getQualityMetrics().getEvidenceCoverageRate();
                    completenessRate = extraction.getQualityMetrics().getCompletenessRate();
                    warningFields = extraction.getQualityMetrics().getWarningFields();
                    failedFields = extraction.getQualityMetrics().getFailedFields();
                }
                
                canGenerateDraft = status == ExtractionQualityStatus.REVIEWED;
            }
            
            WorkbenchDocumentResponse wDoc = WorkbenchDocumentResponse.workbenchBuilder()
                    .id(doc.getId())
                    .projectId(doc.getProjectId())
                    .rawDocumentId(doc.getRawDocumentId())
                    .inputType(doc.getInputType())
                    .sourceType(doc.getSourceType())
                    .fileName(doc.getFileName())
                    .status(doc.getStatus())
                    .uploadedBy(doc.getUploadedBy())
                    .startedAt(doc.getStartedAt())
                    .completedAt(doc.getCompletedAt())
                    .errorMessage(doc.getErrorMessage())
                    .createdAt(doc.getCreatedAt())
                    .latestExtractionId(latestExtractionId)
                    .extractionQualityStatus(status)
                    .evidenceCoverageRate(evidenceCoverageRate)
                    .completenessRate(completenessRate)
                    .warningFields(warningFields)
                    .failedFields(failedFields)
                    .canGenerateDraft(canGenerateDraft)
                    .build();
            documents.add(wDoc);
        }

        // 3. Fetch drafts (Candidate / ProfileUpdateProposal) and map to summaries
        List<CandidateDraftSummary> candidateSummaries = new ArrayList<>();
        List<ProposalDraftSummary> proposalSummaries = new ArrayList<>();
        
        // 4. Fetch submissions (needed for both display and draft-linking)
        List<ProjectTaskSubmission> submissionsEntities = submissionRepository.findByProjectTask_Id(taskId);
        
        if (tType == TaskType.COMPANY_DATA_PREPARATION) {
            List<CompanyCandidate> candidates = candidateRepository.findByTaskId(taskId);
            candidateSummaries = candidates.stream().map(c -> {
                ProjectTaskSubmission linkedSub = submissionsEntities.stream()
                        .filter(s -> "CompanyCandidate".equals(s.getTargetEntityType()) && c.getId().equals(s.getTargetEntityId()))
                        .findFirst().orElse(null);
                return CandidateDraftSummary.builder()
                        .candidateId(c.getId())
                        .candidateName(candidateDisplayName(c))
                        .candidateIndustry(candidateIndustry(c))
                        .status(c.getStatus())
                        .taskId(c.getTaskId())
                        .extractionIds(c.getExtractionIds())
                        .sourceDocumentIds(c.getSourceDocumentIds() != null ? c.getSourceDocumentIds() : List.of())
                        .createdAt(c.getMetadata() != null ? c.getMetadata().getCreatedAt() : null)
                        .hasConflicts(null) // Not stored on entity; available in MergeCandidateResponse at creation time
                        .conflictCount(null)
                        .isUnderReview(linkedSub != null && linkedSub.getStatus() == SubmissionStatus.IN_REVIEW)
                        .isApproved(linkedSub != null && linkedSub.getStatus() == SubmissionStatus.APPROVED)
                        .linkedSubmissionId(linkedSub != null ? linkedSub.getId() : null)
                        .build();
            }).sorted(Comparator
                    .comparingInt((CandidateDraftSummary draft) -> candidateDraftStatusRank(draft.getStatus()))
                    .thenComparing(CandidateDraftSummary::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();

            List<CompanyProfileUpdateProposal> proposals = proposalRepository.findByTaskId(taskId);
            proposalSummaries = proposals.stream().map(p -> {
                ProjectTaskSubmission linkedSub = submissionsEntities.stream()
                        .filter(s -> "CompanyProfileUpdateProposal".equals(s.getTargetEntityType()) && p.getId().equals(s.getTargetEntityId()))
                        .findFirst().orElse(null);
                return ProposalDraftSummary.builder()
                        .proposalId(p.getId())
                        .status(p.getStatus())
                        .taskId(p.getTaskId())
                        .companyProfileId(p.getCompanyProfileId())
                        .extractionIds(p.getExtractionIds())
                        .sourceDocumentIds(p.getSourceDocumentIds())
                        .createdAt(p.getCreatedAt())
                        .hasConflicts(p.getHasConflicts())
                        .conflictCount(p.getConflictCount())
                        .changeSummary(p.getChangeSummary())
                        .isUnderReview(linkedSub != null && linkedSub.getStatus() == SubmissionStatus.IN_REVIEW)
                        .isApproved(linkedSub != null && linkedSub.getStatus() == SubmissionStatus.APPROVED)
                        .linkedSubmissionId(linkedSub != null ? linkedSub.getId() : null)
                        .build();
            }).toList();
        }

        // 5. Map submissions to response DTOs
        List<ProjectTaskSubmissionResponse> submissions = submissionsEntities.stream().map(sub -> 
                ProjectTaskSubmissionResponse.builder()
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
                .build()
        ).toList();

        return ProjectTaskWorkbenchResponse.builder()
                .projectId(projectId)
                .taskId(taskId)
                .taskTitle(task.getTitle())
                .taskType(tType)
                .taskStatus(task.getStatus())
                .projectType(project.getProjectType())
                .projectStatus(project.getStatus())
                .targetCompanyName(project.getTargetCompanyName())
                .targetCompanyProfileId(project.getTargetCompanyProfileId())
                .targetRelationshipType(project.getTargetRelationshipType())
                .availableActions(actions)
                .documents(documents)
                .candidateDrafts(candidateSummaries)
                .profileUpdateProposalDrafts(proposalSummaries)
                .submissions(submissions)
                .build();
    }

    private void syncSubmittedRoleEvaluationState(Long projectId, ProjectTask task, UserDetailsImpl currentUser) {
        List<RoleEvaluationDraft> reviewDrafts = roleEvaluationDraftRepository
                .findByProjectIdAndTaskIdOrderByCreatedAtDesc(projectId, task.getId())
                .stream()
                .filter(draft -> draft.getStatus() == RoleEvaluationStatus.IN_REVIEW || draft.getStatus() == RoleEvaluationStatus.APPROVED)
                .toList();
        if (reviewDrafts.isEmpty()) {
            return;
        }

        Account submittedBy = task.getAssignedToAccount();
        if (submittedBy == null) {
            submittedBy = accountRepository.findById(currentUser.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Account not found"));
        }

        List<ProjectTaskSubmission> submissions = submissionRepository.findByProjectTask_Id(task.getId());
        boolean hasApprovedDraft = reviewDrafts.stream().anyMatch(draft -> draft.getStatus() == RoleEvaluationStatus.APPROVED);
        for (RoleEvaluationDraft draft : reviewDrafts) {
            Account submissionAccount = submittedBy;
            if (draft.getSubmittedByAccountId() != null) {
                submissionAccount = accountRepository.findById(draft.getSubmittedByAccountId()).orElse(submittedBy);
            }
            Account finalSubmissionAccount = submissionAccount;

            ProjectTaskSubmission submission = submissions.stream()
                    .filter(item -> "ROLE_EVALUATION_DRAFT".equals(item.getTargetEntityType())
                            && draft.getId().equals(item.getTargetEntityId()))
                    .findFirst()
                    .orElseGet(() -> ProjectTaskSubmission.builder()
                            .project(task.getProject())
                            .projectTask(task)
                            .submissionType(SubmissionType.ROLE_EVALUATION)
                            .targetEntityType("ROLE_EVALUATION_DRAFT")
                            .targetEntityId(draft.getId())
                            .submittedByAccount(finalSubmissionAccount)
                            .build());
            submission.setStatus(draft.getStatus() == RoleEvaluationStatus.APPROVED ? SubmissionStatus.APPROVED : SubmissionStatus.IN_REVIEW);
            submission.setSubmittedByAccount(submissionAccount);
            submission.setSubmittedAt(draft.getSubmittedAt() != null ? draft.getSubmittedAt() : LocalDateTime.now());
            submissionRepository.save(submission);
        }

        TaskStatus targetStatus = hasApprovedDraft ? TaskStatus.DONE : TaskStatus.IN_REVIEW;
        if (task.getStatus() != targetStatus) {
            task.setStatus(targetStatus);
            task.setCompletedAt(hasApprovedDraft ? LocalDateTime.now() : null);
            projectTaskRepository.save(task);
        }
    }

    private String candidateDisplayName(CompanyCandidate candidate) {
        if (candidate.getIdentity() == null) {
            return "Candidate " + candidate.getId().substring(Math.max(0, candidate.getId().length() - 8));
        }
        if (candidate.getIdentity().getTradeName() != null && !candidate.getIdentity().getTradeName().isBlank()) {
            return candidate.getIdentity().getTradeName();
        }
        if (candidate.getIdentity().getLegalName() != null && !candidate.getIdentity().getLegalName().isBlank()) {
            return candidate.getIdentity().getLegalName();
        }
        return "Candidate " + candidate.getId().substring(Math.max(0, candidate.getId().length() - 8));
    }

    private int candidateDraftStatusRank(CandidateStatus status) {
        if (status == CandidateStatus.DRAFT) return 0;
        if (status == CandidateStatus.PENDING_REVIEW) return 1;
        if (status == CandidateStatus.REJECTED) return 2;
        if (status == CandidateStatus.APPROVED) return 3;
        return 4;
    }

    private String candidateIndustry(CompanyCandidate candidate) {
        if (candidate.getBusiness() == null
                || candidate.getBusiness().getIndustries() == null
                || candidate.getBusiness().getIndustries().isEmpty()) {
            return null;
        }
        return String.join(", ", candidate.getBusiness().getIndustries());
    }

    private List<TaskAction> evaluateAvailableActions(UserDetailsImpl user, ProjectTask task, Project project, TaskType taskType) {
        List<TaskAction> actions = new ArrayList<>();
        boolean isStaff = hasRole(user, SystemRole.BUSINESS_DEVELOPMENT_STAFF);
        boolean isManager = hasRole(user, SystemRole.BUSINESS_DEVELOPMENT_MANAGER);
        boolean isAdmin = hasRole(user, SystemRole.SYSTEM_ADMIN);

        boolean isAssignedToMe = task.getAssignedToAccount() != null && task.getAssignedToAccount().getId().equals(user.getId());

        if (isStaff && !isAdmin && !isManager) {
            if ((task.getStatus() == TaskStatus.TODO || task.getStatus() == TaskStatus.IN_PROGRESS) && isAssignedToMe) {
                if (taskType == TaskType.DOCUMENT_COLLECTION) {
                    actions.add(TaskAction.VIEW_DOCUMENTS);
                    actions.add(TaskAction.UPLOAD_DOCUMENT);
                    actions.add(TaskAction.ADD_MANUAL_DOCUMENT);
                    actions.add(TaskAction.SUBMIT_WORK);
                } else if (taskType == TaskType.COMPANY_DATA_PREPARATION) {
                    actions.add(TaskAction.VIEW_DOCUMENTS);
                    actions.add(TaskAction.RUN_AI_EXTRACTION);
                    actions.add(TaskAction.VIEW_EXTRACTION_RESULT);
                    actions.add(TaskAction.EDIT_EXTRACTION_RESULT);
                    actions.add(TaskAction.REVIEW_EXTRACTION_RESULT);
                    if (project.getProjectType() == ProjectType.RESEARCH_NEW_COMPANY) {
                        actions.add(TaskAction.GENERATE_CANDIDATE_DRAFT);
                        actions.add(TaskAction.VIEW_CANDIDATE_DRAFTS);
                    } else if (project.getProjectType() == ProjectType.UPDATE_EXISTING_COMPANY) {
                        actions.add(TaskAction.GENERATE_PROFILE_UPDATE_PROPOSAL_DRAFT);
                        actions.add(TaskAction.VIEW_PROFILE_UPDATE_PROPOSAL_DRAFTS);
                    }
                    actions.add(TaskAction.SUBMIT_SELECTED_DRAFT);
                } else {
                    // GENERAL_TASK
                    actions.add(TaskAction.SUBMIT_WORK);
                }
            }
            if (task.getStatus() == TaskStatus.IN_REVIEW) {
                actions.add(TaskAction.VIEW_SUBMISSIONS);
            }
        }

        if (isManager || isAdmin) {
            actions.add(TaskAction.VIEW_DOCUMENTS); // Manager always can view docs
            if (taskType == TaskType.COMPANY_DATA_PREPARATION) {
                actions.add(TaskAction.VIEW_EXTRACTION_RESULT);
                actions.add(TaskAction.REVIEW_EXTRACTION_RESULT);
                if (project.getProjectType() == ProjectType.RESEARCH_NEW_COMPANY) {
                    actions.add(TaskAction.VIEW_CANDIDATE_DRAFTS);
                } else if (project.getProjectType() == ProjectType.UPDATE_EXISTING_COMPANY) {
                    actions.add(TaskAction.VIEW_PROFILE_UPDATE_PROPOSAL_DRAFTS);
                }
            }
            if (task.getStatus() == TaskStatus.IN_REVIEW) {
                actions.add(TaskAction.VIEW_SUBMISSIONS);
                actions.add(TaskAction.REVIEW_SUBMISSION);
                actions.add(TaskAction.APPROVE_SUBMISSION);
                actions.add(TaskAction.REQUEST_REVISION);
                actions.add(TaskAction.REJECT_SUBMISSION);
            } else if (task.getStatus() == TaskStatus.DONE || task.getStatus() == TaskStatus.CANCELLED) {
                actions.add(TaskAction.VIEW_SUBMISSIONS);
            }
        }

        return actions;
    }

    private ProjectTaskResponse toResponse(ProjectTask task) {
        String assignedName = null;
        if (task.getAssignedToAccount() != null) {
            assignedName = task.getAssignedToAccount().getEmail(); // fallback to email for MVP
        }

        return ProjectTaskResponse.builder()
                .id(task.getId())
                .projectId(task.getProject().getId())
                .title(task.getTitle())
                .description(task.getDescription())
                .assignedToUserId(task.getAssignedToAccount() != null ? task.getAssignedToAccount().getId() : null)
                .assignedToName(assignedName)
                .createdByUserId(task.getCreatedByAccount() != null ? task.getCreatedByAccount().getId() : null)
                .status(task.getStatus())
                .priority(task.getPriority())
                .dueDate(task.getDueDate())
                .createdAt(task.getCreatedAt())
                .updatedAt(task.getUpdatedAt())
                .completedAt(task.getCompletedAt())
                .taskType(task.getTaskType() != null ? task.getTaskType() : TaskType.GENERAL_TASK)
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
