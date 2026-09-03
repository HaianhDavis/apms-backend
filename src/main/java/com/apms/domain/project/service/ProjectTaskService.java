package com.apms.domain.project.service;

import com.apms.common.enums.*;
import com.apms.common.enums.CandidateStatus;
import com.apms.common.exception.BusinessValidationException;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.ai.AiExtractionCache;
import com.apms.domain.ai.dto.ExtractionQualityStatus;
import com.apms.domain.ai.repository.mongo.AiExtractionCacheRepository;
import com.apms.domain.audit.AuditLog;
import com.apms.domain.audit.repository.sql.AuditLogRepository;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.dto.CreateProjectTaskRequest;
import com.apms.domain.project.dto.ProjectTaskResponse;
import com.apms.domain.project.dto.ProjectKeyResultResponse;
import com.apms.domain.project.dto.UpdateProjectTaskRequest;
import com.apms.domain.project.dto.ProjectTaskWorkbenchResponse;
import com.apms.domain.project.dto.ProjectTaskSubmissionResponse;
import com.apms.domain.project.dto.WorkbenchDocumentResponse;
import com.apms.domain.project.dto.CandidateDraftSummary;
import com.apms.domain.project.dto.ProposalDraftSummary;
import com.apms.domain.document.service.DocumentService;
import com.apms.domain.document.dto.ImportJobResponse;
import com.apms.domain.financial.dto.FinancialResearchResponse;
import com.apms.domain.financial.service.FinancialResearchService;
import com.apms.domain.candidate.repository.mongo.CompanyCandidateRepository;
import com.apms.domain.profile.repository.mongo.CompanyProfileUpdateProposalRepository;
import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.candidate.CompanyCandidate;
import com.apms.domain.profile.CompanyProfileUpdateProposal;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
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
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectTaskService {
    private static final DateTimeFormatter PROJECT_END_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.ENGLISH);

    private final ProjectTaskRepository projectTaskRepository;
    private final ProjectRepository projectRepository;
    private final AccountRepository accountRepository;
    private final AuditLogService auditLogService;
    private final DocumentService documentService;
    private final AiExtractionCacheRepository extractionCacheRepository;
    private final CompanyCandidateRepository candidateRepository;
    private final CompanyProfileUpdateProposalRepository proposalRepository;
    private final ProjectTaskSubmissionRepository submissionRepository;
    private final NotificationService notificationService;
    private final com.apms.domain.profile.repository.mongo.CompanyProfileRepository companyProfileRepository;
    private final AuditLogRepository auditLogRepository;
    private final com.apms.domain.project.repository.sql.ProjectMemberRepository projectMemberRepository;
    private final FinancialResearchService financialResearchService;

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

        validateTaskDueDateWithinProject(request.getDueDate(), project);

        Account assignedTo = null;
        if (request.getAssignedToUserId() != null) {
            if (!projectRepository.existsByIdAndMembersAccountId(projectId, request.getAssignedToUserId())) {
                throw new IllegalArgumentException("Assigned user must be a project member");
            }
            assignedTo = accountRepository.findById(request.getAssignedToUserId())
                    .orElseThrow(() -> new ResourceNotFoundException("Assigned account not found"));
        }

        if (request.getTaskType() == TaskType.COMPANY_NEWS_RESEARCH || request.getTaskType() == TaskType.PARTNER_CONTRACT_COLLECTION) {
            if (!org.springframework.util.StringUtils.hasText(request.getTargetCompanyProfileId())) {
                // Auto-resolve from project if not explicitly provided
                if (org.springframework.util.StringUtils.hasText(project.getTargetCompanyProfileId())) {
                    request.setTargetCompanyProfileId(project.getTargetCompanyProfileId());
                } else {
                    throw new com.apms.common.exception.BusinessValidationException("targetCompanyProfileId is required for " + request.getTaskType().name());
                }
            }
        }

        if (org.springframework.util.StringUtils.hasText(request.getTargetCompanyProfileId())) {
            com.apms.domain.profile.CompanyProfile profile = companyProfileRepository.findByCompanyId(request.getTargetCompanyProfileId())
                    .or(() -> companyProfileRepository.findById(request.getTargetCompanyProfileId()))
                    .orElseThrow(() -> new com.apms.common.exception.BusinessValidationException("Target company profile not found"));
            if (Boolean.TRUE.equals(profile.getIsDeleted())) {
                throw new com.apms.common.exception.BusinessValidationException("Target company profile is deleted");
            }
            request.setTargetCompanyProfileId(profile.getCompanyId());
        }

        if (request.getTaskType() == TaskType.DOCUMENT_COLLECTION) {
            throw new com.apms.common.exception.BusinessValidationException("DOCUMENT_COLLECTION is deprecated. Use COMPANY_DATA_PREPARATION, which includes document collection and upload.");
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
        }

        return toResponse(task);
    }

    @Transactional(readOnly = true)
    public List<com.apms.domain.project.dto.ProjectTaskActivityResponse> getTaskActivity(Long projectId, Long taskId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));
        
        ProjectTask task = projectTaskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found"));

        if (!task.getProject().getId().equals(projectId)) {
            throw new com.apms.common.exception.BusinessValidationException("Task does not belong to the specified project");
        }

        UserDetailsImpl currentUser = getCurrentUser();
        if (currentUser == null) {
            throw new AccessDeniedException("Unauthorized");
        }

        List<ProjectTaskSubmission> submissions = submissionRepository.findByProjectTask_Id(taskId);
        List<String> submissionIds = submissions.stream().map(s -> String.valueOf(s.getId())).toList();

        List<AuditLog> logs = auditLogRepository.findAll((root, query, cb) -> {
            query.orderBy(cb.desc(root.get("timestamp")));
            Predicate taskPredicate = cb.and(
                    cb.equal(root.get("entityType"), "ProjectTask"),
                    cb.equal(root.get("entityId"), String.valueOf(taskId))
            );
            
            if (submissionIds.isEmpty()) {
                return taskPredicate;
            }
            
            Predicate submissionPredicate = cb.and(
                    cb.equal(root.get("entityType"), "ProjectTaskSubmission"),
                    root.get("entityId").in(submissionIds)
            );
            
            return cb.or(taskPredicate, submissionPredicate);
        });

        
        boolean hasGeneratedEvent = logs.stream().anyMatch(l -> 
                l.getAction() != null && l.getAction() == com.apms.common.enums.AuditAction.PROJECT_TASK_CREATED);
                
        List<com.apms.domain.project.dto.ProjectTaskActivityResponse> responseLogs = new ArrayList<>(logs.stream().map(log -> {
            String actorName = "System";
            if (log.getActorAccountId() != null) {
                actorName = accountRepository.findById(log.getActorAccountId())
                        .map(acc -> {
                            String email = acc.getEmail();
                            return email != null ? email.split("@")[0] : "Unknown";
                        })
                        .orElse("Unknown User");
            }

            return com.apms.domain.project.dto.ProjectTaskActivityResponse.builder()
                    .id(log.getId())
                    .actorId(log.getActorAccountId())
                    .actorName(actorName)
                    .action(log.getAction() != null ? log.getAction().name() : "UNKNOWN")
                    .detail(log.getDetail())
                    .occurredAt(log.getTimestamp())
                    .build();
        }).toList());

        if (!hasGeneratedEvent) {
            String detail = "Task generated";
            if (task.getKeyResult() != null) {
                detail = "Task generated from " + task.getKeyResult().getName();
            }
            responseLogs.add(com.apms.domain.project.dto.ProjectTaskActivityResponse.builder()
                    .id(-1L)
                    .actorId(null)
                    .actorName("System")
                    .action(com.apms.common.enums.AuditAction.PROJECT_TASK_CREATED.name())
                    .detail(detail)
                    .occurredAt(task.getCreatedAt())
                    .build());
        }

        // Sort descending by occurredAt
        responseLogs.sort((a, b) -> {
            LocalDateTime aTime = a.getOccurredAt() != null ? a.getOccurredAt() : LocalDateTime.MIN;
            LocalDateTime bTime = b.getOccurredAt() != null ? b.getOccurredAt() : LocalDateTime.MIN;
            int timeCompare = bTime.compareTo(aTime);
            if (timeCompare == 0) {
                return b.getId().compareTo(a.getId());
            }
            return timeCompare;
        });

        return responseLogs;
    }

    @Transactional(readOnly = true)
    public Page<ProjectTaskResponse> getTasks(Long projectId, TaskStatus status, Long assignedToUserId, Pageable pageable, boolean isPoolQuery) {
        Specification<ProjectTask> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("project").get("id"), projectId));

            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (isPoolQuery) {
                predicates.add(cb.isNull(root.get("assignedToAccount")));
            } else if (assignedToUserId != null) {
                predicates.add(cb.equal(root.get("assignedToAccount").get("id"), assignedToUserId));
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
            throw new com.apms.common.exception.BusinessValidationException("Task does not belong to the specified project");
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
            if (request.getStatus() == TaskStatus.CANCELLED && task.getKeyResult() != null) {
                throw new com.apms.common.exception.BusinessValidationException("OKR-generated tasks cannot be cancelled");
            }
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
            if (request.getDueDate() != null) {
                validateTaskDueDateWithinProject(request.getDueDate(), task.getProject());
                task.setDueDate(request.getDueDate());
            }
            if (request.getTaskType() != null && task.getStatus() != TaskStatus.DONE && task.getStatus() != TaskStatus.CANCELLED) {
                task.setTaskType(request.getTaskType());
            }
            if (request.getTargetCompanyProfileId() != null) {
                com.apms.domain.profile.CompanyProfile profile = companyProfileRepository.findByCompanyId(request.getTargetCompanyProfileId())
                        .or(() -> companyProfileRepository.findById(request.getTargetCompanyProfileId()))
                        .orElseThrow(() -> new com.apms.common.exception.BusinessValidationException("Target company profile not found"));
                if (Boolean.TRUE.equals(profile.getIsDeleted())) {
                    throw new com.apms.common.exception.BusinessValidationException("Target company profile is deleted");
                }
                task.setTargetCompanyProfileId(profile.getCompanyId());
            }

            if (request.getAssignedToUserId() != null) {
                Long currentAssignedId = task.getAssignedToAccount() != null ? task.getAssignedToAccount().getId() : null;
                if (!request.getAssignedToUserId().equals(currentAssignedId)) {
                    if (task.getKeyResult() != null) {
                        throw new com.apms.common.exception.BusinessValidationException("OKR-generated tasks must be self-claimed and cannot be manually assigned");
                    }
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

    private void validateTaskDueDateWithinProject(LocalDateTime dueDate, Project project) {
        if (dueDate == null || project == null || project.getPlannedEndDate() == null) {
            return;
        }
        if (dueDate.toLocalDate().isAfter(project.getPlannedEndDate())) {
            throw new BusinessValidationException(
                    "Task due date cannot be later than the project's planned end date ("
                            + project.getPlannedEndDate().format(PROJECT_END_DATE_FORMATTER)
                            + ").");
        }
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

        // 1. Evaluate actions
        List<TaskAction> actions = evaluateAvailableActions(currentUser, task, project, tType);

        // 2. Fetch documents (only metadata/import jobs)
        List<ImportJobResponse> rawDocuments;
        if (tType == TaskType.COMPANY_DATA_PREPARATION) {
            rawDocuments = documentService.getTaskImportJobs(projectId, taskId, false, org.springframework.data.domain.Pageable.unpaged()).getContent();
        } else if (tType == TaskType.FINANCIAL_RESEARCH) {
            rawDocuments = documentService.getTaskImportJobs(projectId, taskId, false, org.springframework.data.domain.Pageable.unpaged()).getContent();
        } else if (tType == TaskType.PARTNER_CONTRACT_COLLECTION) {
            rawDocuments = documentService.getPartnerContractTaskDocuments(projectId, taskId, false, org.springframework.data.domain.Pageable.unpaged()).getContent();
        } else {
            rawDocuments = documentService.getProjectImportJobs(projectId, false, org.springframework.data.domain.Pageable.unpaged()).getContent();
        }

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
        FinancialResearchResponse financialResearch = null;
        if (tType == TaskType.FINANCIAL_RESEARCH) {
            financialResearch = financialResearchService.getResearch(projectId, taskId).orElse(null);
        }

        if (tType == TaskType.COMPANY_DATA_PREPARATION) {
            List<CompanyCandidate> candidates = candidateRepository.findByTaskId(taskId);
            candidateSummaries = candidates.stream().map(c -> {
                ProjectTaskSubmission linkedSub = submissionsEntities.stream()
                        .filter(s -> "CompanyCandidate".equals(s.getTargetEntityType()) && c.getId().equals(s.getTargetEntityId()))
                        .findFirst().orElse(null);
                return CandidateDraftSummary.builder()
                        .candidateId(c.getId())
                        .candidateName(candidateDisplayName(c))
                        .draftName(candidateDisplayName(c))
                        .draftSequence(c.getDraftSequence())
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
            }).toList();

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
                .submittedByName(sub.getSubmittedByAccount() != null ? sub.getSubmittedByAccount().getEmail() : null)
                .submittedRevisionNumber(sub.getSubmittedRevisionNumber())
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
                .targetCompanyProfileId(org.springframework.util.StringUtils.hasText(task.getTargetCompanyProfileId())
                        ? task.getTargetCompanyProfileId()
                        : project.getTargetCompanyProfileId())
                .targetRelationshipType(project.getTargetRelationshipType())
                .availableActions(actions)
                .documents(documents)
                .candidateDrafts(candidateSummaries)
                .profileUpdateProposalDrafts(proposalSummaries)
                .submissions(submissions)
                .financialResearch(financialResearch)
                .build();
    }



    private String candidateDisplayName(CompanyCandidate candidate) {
        if (candidate.getDraftName() != null && !candidate.getDraftName().isBlank()) {
            return candidate.getDraftName();
        }
        if (candidate.getDraftSequence() != null) {
            return "Draft " + candidate.getDraftSequence();
        }
        return "Draft";
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

    private boolean canReviewTask(ProjectTask task, UserDetailsImpl user, Project project) {
        if (task.getStatus() != TaskStatus.IN_REVIEW) return false;

        Long userId = user.getId();
        if (task.getAssignedToAccount() != null && userId.equals(task.getAssignedToAccount().getId())) {
            return false; // self-review protection (assignee)
        }

        java.util.List<ProjectTaskSubmission> taskSubmissions = submissionRepository.findByProjectTask_Id(task.getId());
        ProjectTaskSubmission latestSubmission = taskSubmissions.stream()
                .max(Comparator.comparing(ProjectTaskSubmission::getId))
                .orElse(null);
        if (latestSubmission != null && latestSubmission.getSubmittedByAccount() != null && userId.equals(latestSubmission.getSubmittedByAccount().getId())) {
            return false; // self-review protection (submitter)
        }

        java.util.Optional<com.apms.domain.project.ProjectMember> memberOpt =
                projectMemberRepository.findByProject_IdAndAccount_Id(project.getId(), userId);
        if (memberOpt.isPresent()) {
            com.apms.common.enums.ProjectRole role = memberOpt.get().getProjectRole();
            return role == com.apms.common.enums.ProjectRole.LEADER || role == com.apms.common.enums.ProjectRole.DEPUTY;
        }
        return false;
    }

    private List<TaskAction> evaluateAvailableActions(UserDetailsImpl user, ProjectTask task, Project project, TaskType taskType) {
        List<TaskAction> actions = new ArrayList<>();
        if (project.getStatus() == ProjectStatus.CLOSED || project.getStatus() == ProjectStatus.COMPLETED) {
            return actions;
        }

        boolean isStaff = hasRole(user, SystemRole.BUSINESS_DEVELOPMENT_STAFF);
        boolean isManager = hasRole(user, SystemRole.BUSINESS_DEVELOPMENT_MANAGER);
        boolean isAdmin = hasRole(user, SystemRole.SYSTEM_ADMIN);

        boolean isAssignedToMe = task.getAssignedToAccount() != null && task.getAssignedToAccount().getId() != null && task.getAssignedToAccount().getId().equals(user.getId());

        if (isStaff && !isAdmin && !isManager) {
            boolean isProjectMember = projectRepository.existsByIdAndMembersAccountId(project.getId(), user.getId());
            if (isProjectMember) {
                if (task.getStatus() == TaskStatus.AVAILABLE && task.getAssignedToAccount() == null) {
                    actions.add(TaskAction.CLAIM_TASK);
                } else if (task.getStatus() == TaskStatus.IN_PROGRESS && isAssignedToMe) {
                    actions.add(TaskAction.RELEASE_TASK);
                }
            }

            if ((task.getStatus() == TaskStatus.TODO || task.getStatus() == TaskStatus.IN_PROGRESS) && isAssignedToMe) {
                if (taskType == TaskType.DOCUMENT_COLLECTION) {
                    actions.add(TaskAction.VIEW_DOCUMENTS);
                    actions.add(TaskAction.UPLOAD_DOCUMENT);
                    actions.add(TaskAction.ADD_MANUAL_DOCUMENT);
                    actions.add(TaskAction.SUBMIT_WORK);
                } else if (taskType == TaskType.COMPANY_DATA_PREPARATION) {
                    actions.add(TaskAction.VIEW_DOCUMENTS);
                    actions.add(TaskAction.UPLOAD_DOCUMENT);
                    actions.add(TaskAction.ADD_MANUAL_DOCUMENT);
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
                } else if (taskType == TaskType.PARTNER_CONTRACT_COLLECTION) {
                    actions.add(TaskAction.VIEW_DOCUMENTS);
                    actions.add(TaskAction.UPLOAD_DOCUMENT);
                    actions.add(TaskAction.SUBMIT_WORK);
                } else if (taskType == TaskType.COMPANY_NEWS_RESEARCH) {
                    actions.add(TaskAction.CREATE_NEWS_DRAFT);
                    actions.add(TaskAction.VIEW_NEWS_DRAFTS);
                    actions.add(TaskAction.EDIT_NEWS_DRAFT);
                    actions.add(TaskAction.UPLOAD_NEWS_IMAGE);
                    actions.add(TaskAction.SUBMIT_NEWS_DRAFTS);
                } else if (taskType == TaskType.FINANCIAL_RESEARCH) {
                    actions.add(TaskAction.VIEW_DOCUMENTS);
                    actions.add(TaskAction.UPLOAD_DOCUMENT);
                    actions.add(TaskAction.RUN_FINANCIAL_EXTRACTION);
                    actions.add(TaskAction.VIEW_FINANCIAL_RESEARCH);
                    actions.add(TaskAction.EDIT_FINANCIAL_RESEARCH);
                    actions.add(TaskAction.SUBMIT_FINANCIAL_RESEARCH);
                    actions.add(TaskAction.VERIFY_FINANCIAL_METRIC);
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
            } else if (taskType == TaskType.COMPANY_NEWS_RESEARCH) {
                actions.add(TaskAction.VIEW_NEWS_DRAFTS);
            }
            if (task.getStatus() == TaskStatus.IN_REVIEW || task.getStatus() == TaskStatus.DONE || task.getStatus() == TaskStatus.CANCELLED) {
                actions.add(TaskAction.VIEW_SUBMISSIONS);
            }
        }
        
        if (task.getStatus() == TaskStatus.IN_REVIEW) {
            boolean isGlobalManager = isManager || isAdmin;
            boolean canReview = canReviewTask(task, user, project);
            
            // Check self-review for global manager if they aren't covered by canReviewTask
            if (isGlobalManager && !canReview) {
                Long userId = user.getId();
                boolean isSelfReview = false;
                if (task.getAssignedToAccount() != null && userId.equals(task.getAssignedToAccount().getId())) {
                    isSelfReview = true;
                } else {
                    java.util.List<ProjectTaskSubmission> taskSubmissions = submissionRepository.findByProjectTask_Id(task.getId());
                    ProjectTaskSubmission latestSubmission = taskSubmissions.stream()
                            .max(Comparator.comparing(ProjectTaskSubmission::getId))
                            .orElse(null);
                    if (latestSubmission != null && latestSubmission.getSubmittedByAccount() != null && userId.equals(latestSubmission.getSubmittedByAccount().getId())) {
                        isSelfReview = true;
                    }
                }
                if (!isSelfReview) canReview = true;
            }

            if (canReview) {
                if (!actions.contains(TaskAction.VIEW_SUBMISSIONS)) {
                    actions.add(TaskAction.VIEW_SUBMISSIONS);
                }
                actions.add(TaskAction.REVIEW_SUBMISSION);
                actions.add(TaskAction.APPROVE_SUBMISSION);
                actions.add(TaskAction.REQUEST_REVISION);
                actions.add(TaskAction.REJECT_SUBMISSION);
            }
        }

        return actions;
    }

    private ProjectTaskResponse toResponse(ProjectTask task) {
        String assignedName = null;
        if (task.getAssignedToAccount() != null) {
            assignedName = task.getAssignedToAccount().getEmail(); // fallback to email for MVP
        }

        UserDetailsImpl currentUser = null;
        try {
            currentUser = getCurrentUser();
        } catch (Exception e) {
            // Ignore if no current user (e.g. system calls)
        }
        
        List<TaskAction> actions = null;
        if (currentUser != null && task.getProject() != null) {
            actions = evaluateAvailableActions(currentUser, task, task.getProject(), task.getTaskType() != null ? task.getTaskType() : TaskType.GENERAL_TASK);
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
                .keyResult(task.getKeyResult() != null ? ProjectKeyResultResponse.builder()
                        .id(task.getKeyResult().getId())
                        .type(task.getKeyResult().getType())
                        .name(task.getKeyResult().getName())
                        .description(task.getKeyResult().getDescription())
                        .weight(task.getKeyResult().getWeight())
                        .build() : null)
                .targetCompanyProfileId(org.springframework.util.StringUtils.hasText(task.getTargetCompanyProfileId())
                        ? task.getTargetCompanyProfileId()
                        : task.getProject().getTargetCompanyProfileId())
                .availableActions(actions)
                .build();
    }

    @Transactional
    public ProjectTaskResponse claimTask(Long projectId, Long taskId) {
        UserDetailsImpl currentUser = getCurrentUser();
        if (currentUser == null) throw new AccessDeniedException("Unauthorized");
        if (!hasRole(currentUser, SystemRole.BUSINESS_DEVELOPMENT_STAFF)) {
            throw new AccessDeniedException("Only staff can claim tasks");
        }
        if (!projectRepository.existsByIdAndMembersAccountId(projectId, currentUser.getId())) {
            throw new AccessDeniedException("Staff must be a member of the project to claim tasks");
        }

        ProjectTask task = projectTaskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found"));

        if (task.getProject().getStatus() == ProjectStatus.CLOSED || task.getProject().getStatus() == ProjectStatus.COMPLETED) {
            throw new com.apms.common.exception.BusinessValidationException("Project is " + task.getProject().getStatus() + " and tasks cannot be modified.");
        }

        if (!task.getProject().getId().equals(projectId)) {
            throw new com.apms.common.exception.BusinessValidationException("Task does not belong to project");
        }

        Account account = accountRepository.findById(currentUser.getId()).orElseThrow();

        int rows = projectTaskRepository.claimTaskAtomically(taskId, projectId, account, TaskStatus.AVAILABLE, TaskStatus.IN_PROGRESS);
        if (rows == 0) {
            throw new com.apms.common.exception.BusinessConflictException("Task is no longer available or already claimed.");
        }

        auditLogService.log(currentUser.getId(), AuditAction.PROJECT_TASK_CLAIMED, "ProjectTask", String.valueOf(taskId), "Task claimed by user " + account.getEmail());

        // Re-fetch to return the updated state
        task = projectTaskRepository.findById(taskId).orElseThrow();
        return toResponse(task);
    }

    @Transactional
    public ProjectTaskResponse releaseTask(Long projectId, Long taskId) {
        UserDetailsImpl currentUser = getCurrentUser();
        if (currentUser == null) throw new AccessDeniedException("Unauthorized");
        if (!hasRole(currentUser, SystemRole.BUSINESS_DEVELOPMENT_STAFF)) {
            throw new AccessDeniedException("Only staff can release tasks");
        }
        if (!projectRepository.existsByIdAndMembersAccountId(projectId, currentUser.getId())) {
            throw new AccessDeniedException("Staff must be a member of the project to release tasks");
        }

        ProjectTask task = projectTaskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found"));

        if (task.getProject().getStatus() == ProjectStatus.CLOSED || task.getProject().getStatus() == ProjectStatus.COMPLETED) {
            throw new com.apms.common.exception.BusinessValidationException("Project is " + task.getProject().getStatus() + " and tasks cannot be modified.");
        }

        if (!task.getProject().getId().equals(projectId)) {
            throw new com.apms.common.exception.BusinessValidationException("Task does not belong to project");
        }

        int rows = projectTaskRepository.releaseTaskAtomically(taskId, projectId, currentUser.getId(), TaskStatus.IN_PROGRESS, TaskStatus.AVAILABLE);
        if (rows == 0) {
            throw new com.apms.common.exception.BusinessValidationException("Task cannot be released. It must be IN_PROGRESS and assigned to you.");
        }

        auditLogService.log(currentUser.getId(), AuditAction.PROJECT_TASK_RELEASED, "ProjectTask", String.valueOf(taskId), "Task released by user " + currentUser.getEmail());

        // Re-fetch to return the updated state
        task = projectTaskRepository.findById(taskId).orElseThrow();
        return toResponse(task);
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
