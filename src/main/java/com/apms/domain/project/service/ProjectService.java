package com.apms.domain.project.service;

import com.apms.common.enums.MemberRole;
import com.apms.common.enums.ProjectStatus;
import com.apms.common.enums.ProjectType;
import com.apms.common.exception.BusinessValidationException;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectMember;
import com.apms.domain.project.dto.*;
import com.apms.domain.project.dto.ProjectKeyResultResponse;
import com.apms.domain.project.dto.DuplicateCompanyCheckResponse;
import com.apms.domain.project.ProjectKeyResult;
import com.apms.domain.project.repository.sql.ProjectKeyResultRepository;
import com.apms.domain.project.repository.sql.ProjectMemberRepository;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import com.apms.domain.user.repository.sql.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
import com.apms.domain.document.repository.sql.ImportJobRepository;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.notification.service.NotificationService;
import com.apms.common.enums.AuditAction;
import com.apms.common.enums.TaskStatus;
import com.apms.domain.project.dto.UpdateProjectStatusRequest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Arrays;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectKeyResultRepository projectKeyResultRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final AccountRepository accountRepository;
    private final UserProfileRepository userProfileRepository;
    private final TaskGeneratorService taskGeneratorService;
    private final Neo4jClient neo4jClient;
    private final ProjectTaskRepository projectTaskRepository;
    private final ProjectTaskSubmissionRepository projectTaskSubmissionRepository;
    private final ImportJobRepository importJobRepository;
    private final AuditLogService auditLogService;
    private final com.apms.domain.profile.service.OwnerOrganizationService ownerOrganizationService;
    private final NotificationService notificationService;
    private final com.apms.domain.profile.repository.mongo.CompanyProfileRepository companyProfileRepository;

    // ─────────────────────────────────────────────
    // CREATE
    // ─────────────────────────────────────────────

    @Transactional
    public ProjectResponse createProject(CreateProjectRequest request, Long creatorAccountId) {
        com.apms.common.enums.RelationshipType resolvedRelationshipType = request.getTargetRelationshipType();

        if (request.getProjectType() == ProjectType.UPDATE_EXISTING_COMPANY && resolvedRelationshipType == null) {
            String cypher = """
                MATCH (c1:Company {companyId: $ownerId})-[r]->(c2:Company {companyId: $targetId})
                RETURN type(r) AS relType
                LIMIT 1
                """;
            java.util.Collection<String> relTypes = neo4jClient.query(cypher)
                    .bindAll(Map.of(
                            "ownerId", ownerOrganizationService.getOwnerCompanyId(),
                            "targetId", request.getTargetCompanyProfileId()
                    ))
                    .fetchAs(String.class)
                    .mappedBy((ts, record) -> record.get("relType").asString())
                    .all();

            if (relTypes != null && !relTypes.isEmpty()) {
                String relString = relTypes.iterator().next();
                try {
                    resolvedRelationshipType = com.apms.common.enums.RelationshipType.valueOf(relString);
                } catch (IllegalArgumentException e) {
                    log.warn("Unknown relationship type in Neo4j: {}", relString);
                }
            }

            if (resolvedRelationshipType == null) {
                throw new BusinessValidationException("No existing relationship found for this company. Please provide targetRelationshipType.");
            }
        }

        if (request.getProjectType() == ProjectType.RESEARCH_NEW_COMPANY) {
            if (org.springframework.util.StringUtils.hasText(request.getTargetCompanyTaxCode())) {
                com.apms.domain.project.dto.DuplicateTaxCodeCheckResponse duplicateCheck = checkDuplicateTaxCode(request.getTargetCompanyTaxCode());
                if (duplicateCheck.isExists()) {
                    throw new BusinessValidationException("Duplicate tax code found: " + duplicateCheck.getMatchType());
                }
            }
        }

        validateProjectTypeInvariants(request.getProjectType(),
                request.getTargetCompanyProfileId(),
                request.getTargetCompanyName(),
                resolvedRelationshipType);

        if (request.getPlannedEndDate().isBefore(LocalDate.now())) {
            throw new com.apms.common.exception.BusinessValidationException("Planned end date cannot be before today");
        }

        if (request.getKeyResults() != null && !request.getKeyResults().isEmpty()) {
            int totalWeight = request.getKeyResults().stream().mapToInt(com.apms.domain.project.dto.CreateProjectKeyResultRequest::getWeight).sum();
            if (totalWeight != 100) {
                throw new com.apms.common.exception.BusinessValidationException("KR_WEIGHT_TOTAL_MUST_EQUAL_100", "Total weight of Key Results must be exactly 100.");
            }
            if (request.getKeyResults().stream().anyMatch(kr -> kr.getWeight() <= 0)) {
                throw new com.apms.common.exception.BusinessValidationException("KR_WEIGHT_MUST_BE_POSITIVE", "Key Result weight must be greater than 0.");
            }
            long uniqueTypesCount = request.getKeyResults().stream().map(com.apms.domain.project.dto.CreateProjectKeyResultRequest::getType).distinct().count();
            if (uniqueTypesCount != request.getKeyResults().size()) {
                throw new com.apms.common.exception.BusinessValidationException("KR_DUPLICATE_TYPE", "Duplicate Key Result type found.");
            }
            if (request.getKeyResults().stream().anyMatch(kr -> kr.getType() == com.apms.common.enums.ProjectKeyResultType.CONTRACT_INFORMATION)) {
                if (resolvedRelationshipType != com.apms.common.enums.RelationshipType.PARTNER_WITH &&
                    resolvedRelationshipType != com.apms.common.enums.RelationshipType.CUSTOMER_OF &&
                    resolvedRelationshipType != com.apms.common.enums.RelationshipType.SUPPLIER_OF) {
                    throw new com.apms.common.exception.BusinessValidationException("KR_CONTRACT_INFORMATION_UNSUPPORTED_RELATIONSHIP", "CONTRACT_INFORMATION key result is only supported for PARTNER, CUSTOMER, or SUPPLIER relationships.");
                }
            }
        }

        Project project = Project.builder()
                .projectName(request.getProjectName())
                .projectType(request.getProjectType())
                .targetCompanyProfileId(request.getTargetCompanyProfileId())
                .targetCompanyName(request.getTargetCompanyName())
                .targetCompanyTaxCode(request.getTargetCompanyTaxCode())
                .targetRelationshipType(resolvedRelationshipType)
                .description(request.getDescription())
                .objective(request.getObjective())
                .plannedEndDate(request.getPlannedEndDate())
                .status(ProjectStatus.DRAFT)
                .createdByAccount(accountRepository.getReferenceById(creatorAccountId))
                .build();

        project = projectRepository.save(project);
        
        if (request.getProjectType() == ProjectType.RESEARCH_NEW_COMPANY && !org.springframework.util.StringUtils.hasText(request.getTargetCompanyProfileId())) {
            com.apms.domain.profile.CompanyProfile newProfile = new com.apms.domain.profile.CompanyProfile();
            newProfile.setCompanyId(java.util.UUID.randomUUID().toString());
            newProfile.setReviewStatus("PENDING_RESEARCH");
            
            com.apms.domain.profile.CompanyProfile.Identity identity = new com.apms.domain.profile.CompanyProfile.Identity();
            identity.setLegalName(request.getTargetCompanyName());
            newProfile.setIdentity(identity);
            
            com.apms.domain.profile.CompanyProfile.SourceRefs refs = new com.apms.domain.profile.CompanyProfile.SourceRefs();
            refs.setProjectIds(new java.util.HashSet<>(java.util.List.of(String.valueOf(project.getId()))));
            newProfile.setSourceRefs(refs);
            
            newProfile = companyProfileRepository.save(newProfile);
            
            project.setTargetCompanyProfileId(newProfile.getCompanyId());
            project = projectRepository.save(project);
        }
        
        final Project finalProject = project;

        if (request.getKeyResults() != null && !request.getKeyResults().isEmpty()) {
            List<ProjectKeyResult> keyResultsToSave = request.getKeyResults().stream().map(krReq -> ProjectKeyResult.builder()
                .project(finalProject)
                .type(krReq.getType())
                .name(krReq.getType().getDisplayName())
                .description(krReq.getType().getDescription())
                .weight(krReq.getWeight())
                .build()
            ).collect(Collectors.toList());
            List<ProjectKeyResult> savedKrs = projectKeyResultRepository.saveAll(keyResultsToSave);
            taskGeneratorService.generateTasksForProject(savedKrs, creatorAccountId);
        }

        // Creator is automatically added as MANAGER
        ProjectMember creator = ProjectMember.builder()
                .project(project)
                .account(accountRepository.getReferenceById(creatorAccountId))
                .memberRole(MemberRole.MANAGER)
                .build();
        projectMemberRepository.save(creator);

        log.info("Project created: id={}, type={}, createdBy={}", project.getId(), project.getProjectType(), creatorAccountId);
        return toResponse(project, List.of(creator));
    }

    // ─────────────────────────────────────────────
    // READ
    // ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ProjectResponse getProjectById(Long id) {
        Project project = findProjectOrThrow(id);
        List<ProjectMember> members = projectMemberRepository.findByProject_Id(id);
        return toResponse(project, members);
    }

    @Transactional(readOnly = true)
    public Page<ProjectResponse> getAllProjects(ProjectStatus status, ProjectType type, Pageable pageable) {
        return getAllProjects(status, type, pageable, null, false);
    }

    @Transactional(readOnly = true)
    public Page<ProjectResponse> getAllProjects(ProjectStatus status, ProjectType type, Pageable pageable, Long accountId, boolean restrictToMembership) {
        Page<Project> page;

        if (restrictToMembership && accountId != null) {
            page = projectRepository.findVisibleProjectsForMember(accountId, status, type, pageable);
        } else if (status != null && type != null) {
            page = projectRepository.findByStatusAndProjectType(status, type, pageable);
        } else if (status != null) {
            page = projectRepository.findByStatus(status, pageable);
        } else if (type != null) {
            page = projectRepository.findByProjectType(type, pageable);
        } else {
            page = projectRepository.findAll(pageable);
        }

        List<Long> projectIds = page.getContent().stream().map(Project::getId).collect(Collectors.toList());
        Map<Long, ProjectTaskRepository.ProjectTaskStats> statsMap = projectIds.isEmpty() ? java.util.Collections.emptyMap() :
                projectTaskRepository.getProjectTaskStatsIn(projectIds, TaskStatus.DONE, TaskStatus.CANCELLED)
                        .stream().collect(Collectors.toMap(ProjectTaskRepository.ProjectTaskStats::getProjectId, s -> s));

        return page.map(p -> {
            List<ProjectMember> members = projectMemberRepository.findByProject_Id(p.getId());
            return toResponse(p, members, statsMap.get(p.getId()));
        });
    }

    @Transactional(readOnly = true)
    public List<ProjectMemberResponse> getProjectMembers(Long projectId) {
        findProjectOrThrow(projectId);
        return projectMemberRepository.findByProject_Id(projectId)
                .stream()
                .map(this::toMemberResponse)
                .collect(Collectors.toList());
    }

    // ─────────────────────────────────────────────
    // UPDATE
    // ─────────────────────────────────────────────

    @Transactional
    public ProjectResponse updateProject(Long id, UpdateProjectRequest request) {
        Project project = findProjectOrThrow(id);

        if (StringUtils.hasText(request.getProjectName())) {
            project.setProjectName(request.getProjectName());
        }
        if (request.getDescription() != null) {
            project.setDescription(request.getDescription());
        }
        if (request.getObjective() != null) {
            project.setObjective(request.getObjective());
        }
        if (request.getTargetRelationshipType() != null) {
            project.setTargetRelationshipType(request.getTargetRelationshipType());
        }
        if (request.getPlannedEndDate() != null) {
            LocalDate logicalStartDate = project.getCreatedAt() != null ? project.getCreatedAt().toLocalDate() : LocalDate.now();
            if (request.getPlannedEndDate().isBefore(logicalStartDate)) {
                throw new com.apms.common.exception.BusinessValidationException("Planned end date cannot be before project start date");
            }
            project.setPlannedEndDate(request.getPlannedEndDate());
        }
        project = projectRepository.save(project);
        List<ProjectMember> members = projectMemberRepository.findByProject_Id(id);
        return toResponse(project, members);
    }

    @Transactional
    public ProjectResponse updateProjectStatus(Long id, UpdateProjectStatusRequest request, Long actorId) {
        Project project = findProjectOrThrow(id);
        ProjectStatus oldStatus = project.getStatus();
        ProjectStatus newStatus = request.getStatus();

        if (oldStatus == newStatus) {
            return toResponse(project, projectMemberRepository.findByProject_Id(id));
        }

        validateStatusTransition(oldStatus, newStatus, request.getForce());

        if (newStatus == ProjectStatus.COMPLETED && !Boolean.TRUE.equals(request.getForce())) {
            int unfinishedCount = projectTaskRepository.countByProjectIdAndStatusIn(
                    id, Arrays.asList(TaskStatus.TODO, TaskStatus.AVAILABLE, TaskStatus.IN_PROGRESS, TaskStatus.IN_REVIEW, TaskStatus.BLOCKED));
            if (unfinishedCount > 0) {
                throw new BusinessValidationException("Project cannot be completed while there are unfinished tasks (" + unfinishedCount + ").");
            }
            
            List<ProjectKeyResult> krs = projectKeyResultRepository.findByProject_Id(id);
            if (krs != null && !krs.isEmpty()) {
                int okrProgress = calculateOkrProgress(id, krs);
                if (okrProgress < 100) {
                    throw new BusinessValidationException("Project cannot be completed until OKR progress is 100%.");
                }
            }
        }

        project.setStatus(newStatus);
        project = projectRepository.save(project);

        AuditAction action = AuditAction.PROJECT_STATUS_CHANGED;
        if (newStatus == ProjectStatus.COMPLETED) action = AuditAction.PROJECT_COMPLETED;
        else if (newStatus == ProjectStatus.CANCELLED) action = AuditAction.PROJECT_CANCELLED;
        else if (newStatus == ProjectStatus.ARCHIVED) action = AuditAction.PROJECT_ARCHIVED;

        String detail = String.format("Status changed from %s to %s. Note: %s", oldStatus, newStatus, request.getNote() != null ? request.getNote() : "");
        auditLogService.log(actorId, action, "Project", String.valueOf(project.getId()), detail);

        List<ProjectMember> members = projectMemberRepository.findByProject_Id(id);
        return toResponse(project, members);
    }

    private void validateStatusTransition(ProjectStatus oldStatus, ProjectStatus newStatus, Boolean force) {
        boolean valid = false;
        switch (oldStatus) {
            case DRAFT -> {
                if (newStatus == ProjectStatus.ACTIVE || newStatus == ProjectStatus.CANCELLED) valid = true;
                if (newStatus == ProjectStatus.COMPLETED && Boolean.TRUE.equals(force)) valid = true;
            }
            case ACTIVE -> {
                if (newStatus == ProjectStatus.COMPLETED || newStatus == ProjectStatus.CANCELLED) valid = true;
            }
            case COMPLETED, CANCELLED -> {
                if (newStatus == ProjectStatus.ARCHIVED) valid = true;
            }
            case ARCHIVED -> valid = false;
        }

        if (!valid) {
            throw new BusinessValidationException("Invalid project status transition: " + oldStatus + " -> " + newStatus + ". Complete or cancel the project before archiving.");
        }
    }

    // ─────────────────────────────────────────────
    // MEMBER MANAGEMENT
    // ─────────────────────────────────────────────

    @Transactional
    public void deleteProject(Long id, Long actorId) {
        Project project = findProjectOrThrow(id);

        if (project.getStatus() != ProjectStatus.DRAFT && project.getStatus() != ProjectStatus.COMPLETED) {
            throw new BusinessValidationException(
                    "Only draft or done projects can be deleted. In progress projects cannot be deleted.");
        }

        String projectName = project.getProjectName();
        ProjectStatus status = project.getStatus();

        projectTaskSubmissionRepository.deleteByProject_Id(id);
        projectTaskRepository.deleteByProjectId(id);
        importJobRepository.deleteByProject_Id(id);
        projectMemberRepository.deleteByProject_Id(id);
        projectRepository.delete(project);

        auditLogService.log(actorId, AuditAction.PROJECT_ARCHIVED, "Project", String.valueOf(id),
                "Project deleted: " + projectName + " (" + status + ")");
        log.info("Project deleted: id={}, name={}, status={}, actor={}", id, projectName, status, actorId);
    }

    @Transactional
    public ProjectMemberResponse addMember(Long projectId, AddMemberRequest request, Long actorId) {
        findProjectOrThrow(projectId);

        Account account = resolveMemberAccount(request);
        Long accountId = account.getId();

        if (Boolean.FALSE.equals(account.getIsActive())) {
            throw new BusinessValidationException("Account is disabled: " + account.getEmail());
        }

        if (projectMemberRepository.existsByProject_IdAndAccount_Id(projectId, accountId)) {
            throw new BusinessValidationException("Account " + accountId + " is already a member of project " + projectId);
        }

        Project project = findProjectOrThrow(projectId);
        ProjectMember member = ProjectMember.builder()
                .project(project)
                .account(account)
                .memberRole(request.getMemberRole())
                .build();

        member = projectMemberRepository.save(member);
        if (request.getMemberRole() == MemberRole.STAFF) {
            Account sender = actorId != null ? accountRepository.findById(actorId).orElse(null) : null;
            notificationService.notifyProjectMemberAdded(project, account, sender);
            
            // Phase 6: Notify available tasks
            if (projectTaskRepository.countByProjectIdAndStatusIn(projectId, java.util.List.of(com.apms.common.enums.TaskStatus.AVAILABLE)) > 0) {
                notificationService.notifyTasksAvailable(project, account, sender);
            }
        }
        log.info("Member added: projectId={}, accountId={}, email={}, role={}", projectId, accountId, account.getEmail(), request.getMemberRole());
        return toMemberResponse(member);
    }

    private Account resolveMemberAccount(AddMemberRequest request) {
        if (request.getAccountId() != null) {
            return accountRepository.findById(request.getAccountId())
                    .orElseThrow(() -> new ResourceNotFoundException("Account not found with id: " + request.getAccountId()));
        }

        if (StringUtils.hasText(request.getEmail())) {
            String email = request.getEmail().trim();
            return accountRepository.findByEmailIgnoreCase(email)
                    .orElseThrow(() -> new ResourceNotFoundException("Account not found with email: " + email));
        }

        throw new BusinessValidationException("Either accountId or email is required");
    }

    @Transactional
    public void removeMember(Long projectId, Long accountId) {
        findProjectOrThrow(projectId);

        if (!projectMemberRepository.existsByProject_IdAndAccount_Id(projectId, accountId)) {
            throw new ResourceNotFoundException("Account " + accountId + " is not a member of project " + projectId);
        }

        // Prevent removing the last MANAGER
        List<ProjectMember> members = projectMemberRepository.findByProject_Id(projectId);
        long managerCount = members.stream()
                .filter(m -> m.getMemberRole() == MemberRole.MANAGER)
                .count();
        ProjectMember toRemove = projectMemberRepository.findByProject_IdAndAccount_Id(projectId, accountId)
                .orElseThrow();
        if (toRemove.getMemberRole() == MemberRole.MANAGER && managerCount <= 1) {
            throw new BusinessValidationException("Cannot remove the last MANAGER from a project.");
        }

        // Prevent removing staff if they have active or in-progress tasks assigned
        boolean hasActiveTasks = projectTaskRepository.existsByProjectIdAndAssignedToAccountIdAndStatusNotIn(
                projectId, accountId, List.of(TaskStatus.DONE, TaskStatus.CANCELLED));
        if (hasActiveTasks) {
            throw new BusinessValidationException("Cannot remove member from project because they have active or in-progress tasks assigned. All assigned tasks must be completed before removal.");
        }

        projectMemberRepository.deleteByProject_IdAndAccount_Id(projectId, accountId);
        log.info("Member removed: projectId={}, accountId={}", projectId, accountId);

        try {
            Project project = findProjectOrThrow(projectId);
            Account removedAccount = accountRepository.findById(accountId).orElse(null);
            org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            Account sender = null;
            if (auth != null && auth.getPrincipal() instanceof com.apms.security.UserDetailsImpl u) {
                sender = accountRepository.findById(u.getId()).orElse(null);
            }
            if (removedAccount != null && project != null) {
                notificationService.notifyProjectMemberRemoved(project, removedAccount, sender);
            }
        } catch (Exception e) {
            log.warn("Failed to send project member removed notification: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    // INVARIANT VALIDATION
    // ─────────────────────────────────────────────

    /**
     * Enforces the three project type invariants:
     *
     * UPDATE_EXISTING_COMPANY:
     *   - targetCompanyProfileId MUST be provided (not blank)
     *   - targetCompanyName MUST be provided (not blank)
     *
     * RESEARCH_NEW_COMPANY:
     *   - targetCompanyProfileId MUST be null or blank
     *   - targetCompanyName MUST be provided (not blank)
     *
     * RESEARCH_MULTIPLE_COMPANIES:
     *   - targetCompanyProfileId MUST be null or blank
     *   - targetCompanyName MUST be provided (not blank — used as research scope)
     */
    private void validateProjectTypeInvariants(ProjectType type,
                                               String targetCompanyProfileId,
                                               String targetCompanyName,
                                               com.apms.common.enums.RelationshipType targetRelationshipType) {
        if (!StringUtils.hasText(targetCompanyName)) {
            throw new BusinessValidationException("targetCompanyName is required for all project types.");
        }
        if (type == ProjectType.RESEARCH_NEW_COMPANY && targetRelationshipType == null) {
            throw new BusinessValidationException("targetRelationshipType is required for RESEARCH_NEW_COMPANY projects.");
        }

        switch (type) {
            case UPDATE_EXISTING_COMPANY -> {
                if (!StringUtils.hasText(targetCompanyProfileId)) {
                    throw new BusinessValidationException(
                            "targetCompanyProfileId is required when projectType is UPDATE_EXISTING_COMPANY.");
                }
                ownerOrganizationService.validateTargetIsNotOwner(targetCompanyProfileId);
            }
            case RESEARCH_NEW_COMPANY -> {
                if (StringUtils.hasText(targetCompanyProfileId)) {
                    throw new BusinessValidationException(
                            "targetCompanyProfileId must be null for projectType " + type + ".");
                }
            }
        }
    }

    // ─────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────
    
    private int calculateOkrProgress(Long projectId, List<ProjectKeyResult> krs) {
        if (krs == null || krs.isEmpty()) {
            return 0;
        }
        List<com.apms.domain.project.ProjectTask> projectTasks = projectTaskRepository.findByProject_Id(projectId);
        java.util.Map<Long, List<com.apms.domain.project.ProjectTask>> tasksByKrId = projectTasks.stream()
                .filter(t -> t.getKeyResult() != null)
                .collect(Collectors.groupingBy(t -> t.getKeyResult().getId()));
        
        int totalOkrProgress = 0;
        for (ProjectKeyResult kr : krs) {
            List<com.apms.domain.project.ProjectTask> krTasks = tasksByKrId.getOrDefault(kr.getId(), java.util.Collections.emptyList());
            if (!krTasks.isEmpty()) {
                boolean allDone = krTasks.stream().allMatch(t -> t.getStatus() == TaskStatus.DONE);
                if (allDone) {
                    totalOkrProgress += (kr.getWeight() != null ? kr.getWeight() : 0);
                }
            }
        }
        return Math.min(100, totalOkrProgress);
    }

    private Project findProjectOrThrow(Long id) {
        return projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found with id: " + id));
    }

    private ProjectResponse toResponse(Project project, List<ProjectMember> members) {
        ProjectTaskRepository.ProjectTaskStats stats = projectTaskRepository.getProjectTaskStatsIn(
                List.of(project.getId()), TaskStatus.DONE, TaskStatus.CANCELLED)
                .stream().findFirst().orElse(null);
        return toResponse(project, members, stats);
    }

    private ProjectResponse toResponse(Project project, List<ProjectMember> members, ProjectTaskRepository.ProjectTaskStats stats) {
        int totalTasks = stats != null && stats.getTotalTasks() != null ? stats.getTotalTasks().intValue() : 0;
        int completedTasks = stats != null && stats.getCompletedTasks() != null ? stats.getCompletedTasks().intValue() : 0;
        int progressPercentage = totalTasks == 0 ? 0 : Math.min(100, (completedTasks * 100) / totalTasks);

        boolean isOverdue = false;
        if (project.getPlannedEndDate() != null && progressPercentage < 100) {
            if (LocalDate.now().isAfter(project.getPlannedEndDate())) {
                isOverdue = true;
            }
        }

        List<ProjectMemberResponse> memberResponses = members.stream()
                .map(this::toMemberResponse)
                .collect(Collectors.toList());

        Long managerId = null;
        String managerName = null;
        List<ProjectMemberResponse> managers = memberResponses.stream()
                .filter(m -> m.getMemberRole() == com.apms.common.enums.MemberRole.MANAGER)
                .collect(Collectors.toList());
        
        if (!managers.isEmpty()) {
            managerId = managers.get(0).getAccountId();
            managerName = managers.stream().map(ProjectMemberResponse::getFullName).collect(Collectors.joining(", "));
        }

        List<com.apms.domain.project.dto.ProjectKeyResultResponse> krResponses = null;
        List<ProjectKeyResult> krs = projectKeyResultRepository.findByProject_Id(project.getId());
        if (krs != null && !krs.isEmpty()) {
            List<com.apms.domain.project.ProjectTask> projectTasks = projectTaskRepository.findByProject_Id(project.getId());
            java.util.Map<Long, List<com.apms.domain.project.ProjectTask>> tasksByKrId = projectTasks.stream()
                    .filter(t -> t.getKeyResult() != null)
                    .collect(Collectors.groupingBy(t -> t.getKeyResult().getId()));
            
            int totalOkrProgress = 0;
            krResponses = new java.util.ArrayList<>();
            for (ProjectKeyResult kr : krs) {
                List<com.apms.domain.project.ProjectTask> krTasks = tasksByKrId.getOrDefault(kr.getId(), java.util.Collections.emptyList());
                int krProgress = 0;
                if (!krTasks.isEmpty()) {
                    boolean allDone = krTasks.stream().allMatch(t -> t.getStatus() == TaskStatus.DONE);
                    if (allDone) {
                        krProgress = 100;
                        totalOkrProgress += (kr.getWeight() != null ? kr.getWeight() : 0);
                    }
                }
                krResponses.add(com.apms.domain.project.dto.ProjectKeyResultResponse.builder()
                    .id(kr.getId())
                    .type(kr.getType())
                    .name(kr.getName())
                    .description(kr.getDescription())
                    .weight(kr.getWeight())
                    .progress(krProgress)
                    .build());
            }
            progressPercentage = Math.min(100, totalOkrProgress);
            
            // Re-evaluate overdue based on the newly calculated OKR progress
            isOverdue = false;
            if (project.getPlannedEndDate() != null && progressPercentage < 100) {
                if (LocalDate.now().isAfter(project.getPlannedEndDate())) {
                    isOverdue = true;
                }
            }
        }

        return ProjectResponse.builder()
                .id(project.getId())
                .projectName(project.getProjectName())
                .projectType(project.getProjectType())
                .targetCompanyProfileId(project.getTargetCompanyProfileId())
                .targetCompanyName(project.getTargetCompanyName())
                .targetCompanyTaxCode(project.getTargetCompanyTaxCode())
                .targetRelationshipType(project.getTargetRelationshipType())
                .description(project.getDescription())
                .objective(project.getObjective())
                .keyResults(krResponses)
                .status(project.getStatus())
                .createdBy(project.getCreatedById())
                .createdAt(project.getCreatedAt())
                .updatedAt(project.getUpdatedAt())
                .plannedEndDate(project.getPlannedEndDate())
                .managerId(managerId)
                .managerName(managerName)
                .totalTasks(totalTasks)
                .completedTasks(completedTasks)
                .progressPercentage(progressPercentage)
                .isOverdue(isOverdue)
                .members(memberResponses)
                .build();
    }

    private ProjectMemberResponse toMemberResponse(ProjectMember m) {
        Account account = m.getAccount();
        Long accountId = m.getAccountId();
        String email = account != null ? account.getEmail() : null;
        String fullName = accountId == null ? null : userProfileRepository.findByAccountId(accountId)
                .map(profile -> (profile.getFirstName() + " " + profile.getLastName()).trim())
                .filter(name -> !name.isBlank())
                .orElse(email);

        return ProjectMemberResponse.builder()
                .id(m.getId())
                .accountId(accountId)
                .email(email)
                .fullName(fullName)
                .memberRole(m.getMemberRole())
                .joinedAt(m.getJoinedAt())
                .build();
    }

    public DuplicateCompanyCheckResponse checkDuplicateCompanyName(String companyName, Long excludeProjectId) {
        if (!StringUtils.hasText(companyName) || companyName.trim().length() < 2) {
            return DuplicateCompanyCheckResponse.builder()
                    .duplicate(false)
                    .matchingProjects(List.of())
                    .build();
        }

        List<Project> matches = projectRepository.findByTargetCompanyNameContainingIgnoreCase(
                companyName.trim(), excludeProjectId);

        List<DuplicateCompanyCheckResponse.MatchingProject> matchingProjects = matches.stream()
                .map(p -> DuplicateCompanyCheckResponse.MatchingProject.builder()
                        .id(p.getId())
                        .projectName(p.getProjectName())
                        .targetCompanyName(p.getTargetCompanyName())
                        .status(p.getStatus().name())
                        .projectType(p.getProjectType().name())
                        .build())
                .toList();

        return DuplicateCompanyCheckResponse.builder()
                .duplicate(!matchingProjects.isEmpty())
                .matchingProjects(matchingProjects)
                .build();
    }

    public com.apms.domain.project.dto.DuplicateTaxCodeCheckResponse checkDuplicateTaxCode(String taxCode) {
        if (!org.springframework.util.StringUtils.hasText(taxCode)) {
            return com.apms.domain.project.dto.DuplicateTaxCodeCheckResponse.builder()
                    .exists(false)
                    .build();
        }
        String normalizedTaxCode = taxCode.replaceAll("[\\s\\-]", "").trim();

        // Check Profile
        Optional<com.apms.domain.profile.CompanyProfile> profileOpt = companyProfileRepository.findByIdentityTaxCode(normalizedTaxCode);
        if (profileOpt.isPresent()) {
            com.apms.domain.profile.CompanyProfile profile = profileOpt.get();
            return com.apms.domain.project.dto.DuplicateTaxCodeCheckResponse.builder()
                    .exists(true)
                    .matchType("COMPANY_PROFILE")
                    .companyProfileId(profile.getCompanyId())
                    .companyName(profile.getIdentity() != null ? profile.getIdentity().getLegalName() : null)
                    .taxCode(profile.getIdentity() != null ? profile.getIdentity().getTaxCode() : null)
                    .build();
        }

        // Check Active Project
        List<Project> activeProjects = projectRepository.findActiveProjectsByTargetCompanyTaxCode(normalizedTaxCode);
        if (!activeProjects.isEmpty()) {
            Project project = activeProjects.get(0);
            return com.apms.domain.project.dto.DuplicateTaxCodeCheckResponse.builder()
                    .exists(true)
                    .matchType("ACTIVE_PROJECT")
                    .projectId(project.getId())
                    .companyName(project.getTargetCompanyName())
                    .taxCode(project.getTargetCompanyTaxCode())
                    .build();
        }

        return com.apms.domain.project.dto.DuplicateTaxCodeCheckResponse.builder()
                .exists(false)
                .build();
    }
}
