package com.apms.domain.project.service;

import com.apms.common.enums.ProjectRole;
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
    private final ProjectTargetProfileResolver projectTargetProfileResolver;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apms.domain.candidate.repository.mongo.CompanyCandidateRepository companyCandidateRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apms.domain.financial.repository.FinancialResearchRepository financialResearchRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apms.domain.contract.repository.mongo.ContractResearchRepository contractResearchRepository;

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
        
        if (project.getProjectType() == ProjectType.RESEARCH_NEW_COMPANY && projectTargetProfileResolver != null) {
            com.apms.domain.profile.CompanyProfile shell = projectTargetProfileResolver.getOrCreateProjectProfileShell(project, creatorAccountId);
            if (shell != null && !shell.getCompanyId().equals(project.getTargetCompanyProfileId())) {
                project.setTargetCompanyProfileId(shell.getCompanyId());
                project = projectRepository.save(project);
            }
        } else if (org.springframework.util.StringUtils.hasText(project.getTargetCompanyProfileId())) {
            syncTargetIdentity(project.getTargetCompanyProfileId(), project.getTargetCompanyTaxCode());
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
                .projectRole(ProjectRole.LEADER)
                .build();
        projectMemberRepository.save(creator);

        log.info("Project created: id={}, type={}, createdBy={}", project.getId(), project.getProjectType(), creatorAccountId);
        return toResponse(project, List.of(creator));
    }

    // ─────────────────────────────────────────────
    // READ
    // ─────────────────────────────────────────────

    @Transactional
    public ProjectResponse getProjectById(Long id) {
        Project project = findProjectOrThrow(id);
        if (project.getProjectType() == ProjectType.RESEARCH_NEW_COMPANY && !org.springframework.util.StringUtils.hasText(project.getTargetCompanyProfileId()) && projectTargetProfileResolver != null) {
            com.apms.domain.profile.CompanyProfile shell = projectTargetProfileResolver.getOrCreateProjectProfileShell(project, project.getCreatedById());
            if (shell != null && !shell.getCompanyId().equals(project.getTargetCompanyProfileId())) {
                project.setTargetCompanyProfileId(shell.getCompanyId());
                project = projectRepository.save(project);
            }
        }
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
        validateProjectNotClosedOrCompleted(project);

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

        // Structural Edits for DRAFT Projects
        if (project.getStatus() == ProjectStatus.DRAFT) {
            if (request.getTargetCompanyName() != null) {
                project.setTargetCompanyName(request.getTargetCompanyName());
            }
            if (request.getTargetCompanyTaxCode() != null) {
                if (org.springframework.util.StringUtils.hasText(request.getTargetCompanyTaxCode()) && 
                    !request.getTargetCompanyTaxCode().equals(project.getTargetCompanyTaxCode())) {
                    com.apms.domain.project.dto.DuplicateTaxCodeCheckResponse duplicateCheck = checkDuplicateTaxCode(request.getTargetCompanyTaxCode());
                    if (duplicateCheck.isExists()) {
                        throw new BusinessValidationException("Duplicate tax code found: " + duplicateCheck.getMatchType());
                    }
                }
                project.setTargetCompanyTaxCode(request.getTargetCompanyTaxCode());
            }
            if (request.getTargetCompanyProfileId() != null) {
                project.setTargetCompanyProfileId(request.getTargetCompanyProfileId());
            }
            
            if (org.springframework.util.StringUtils.hasText(project.getTargetCompanyProfileId())) {
                syncTargetIdentity(project.getTargetCompanyProfileId(), project.getTargetCompanyTaxCode());
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
                    com.apms.common.enums.RelationshipType rel = project.getTargetRelationshipType();
                    if (rel != com.apms.common.enums.RelationshipType.PARTNER_WITH &&
                        rel != com.apms.common.enums.RelationshipType.CUSTOMER_OF &&
                        rel != com.apms.common.enums.RelationshipType.SUPPLIER_OF) {
                        throw new com.apms.common.exception.BusinessValidationException("KR_CONTRACT_INFORMATION_UNSUPPORTED_RELATIONSHIP", "CONTRACT_INFORMATION key result is only supported for PARTNER, CUSTOMER, or SUPPLIER relationships.");
                    }
                }

                // Delete existing KRs and Tasks
                projectTaskRepository.deleteByProjectId(id);
                projectKeyResultRepository.deleteByProjectId(id);

                final Project lambdaProject = project;
                List<ProjectKeyResult> keyResultsToSave = request.getKeyResults().stream().map(krReq -> ProjectKeyResult.builder()
                    .project(lambdaProject)
                    .type(krReq.getType())
                    .name(krReq.getType().getDisplayName())
                    .description(krReq.getType().getDescription())
                    .weight(krReq.getWeight())
                    .build()
                ).collect(Collectors.toList());
                List<ProjectKeyResult> savedKrs = projectKeyResultRepository.saveAll(keyResultsToSave);

                // Regenerate Tasks
                // Assume creatorAccountId is the one who created the project (fallback to 1L if null, but this shouldn't happen)
                Long creatorId = project.getCreatedByAccount() != null ? project.getCreatedByAccount().getId() : 1L;
                taskGeneratorService.generateTasksForProject(savedKrs, creatorId);
            }
        } else {
            // Validate ACTIVE projects don't structurally modify KR configuration
            if (request.getKeyResults() != null && !request.getKeyResults().isEmpty()) {
                throw new com.apms.common.exception.BusinessValidationException("Cannot modify Key Results configuration after project is ACTIVE.");
            }
        }

        project = projectRepository.save(project);
        List<ProjectMember> members = projectMemberRepository.findByProject_Id(id);
        return toResponse(project, members);
    }

    private void validateProjectNotClosedOrCompleted(Project project) {
        if (project.getStatus() == ProjectStatus.CLOSED || project.getStatus() == ProjectStatus.COMPLETED) {
            throw new BusinessValidationException("Project is " + project.getStatus() + " and cannot be modified.");
        }
    }

    @Transactional
    public ProjectResponse closeProject(Long id, CloseProjectRequest request, Long actorId) {
        Project project = findProjectOrThrow(id);
        
        if (project.getStatus() != ProjectStatus.ACTIVE) {
            throw new BusinessValidationException("Only ACTIVE projects can be closed.");
        }
        
        ProjectMember actor = getProjectMember(id, actorId);
        if (actor.getProjectRole() != ProjectRole.LEADER) {
            throw new BusinessValidationException("Only the project Leader can close the project.");
        }

        List<ProjectMember> members = projectMemberRepository.findByProject_Id(id);
        ProjectTaskRepository.ProjectTaskStats stats = projectTaskRepository.getProjectTaskStatsIn(
                List.of(id), TaskStatus.DONE, TaskStatus.CANCELLED)
                .stream().findFirst().orElse(null);

        int totalTasks = stats != null && stats.getTotalTasks() != null ? stats.getTotalTasks().intValue() : 0;
        int completedTasks = stats != null && stats.getCompletedTasks() != null ? stats.getCompletedTasks().intValue() : 0;
        int progressPercentage = totalTasks == 0 ? 0 : Math.min(100, (completedTasks * 100) / totalTasks);

        List<ProjectKeyResult> krs = projectKeyResultRepository.findByProject_Id(project.getId());
        if (krs != null && !krs.isEmpty()) {
            int totalOkrProgress = calculateOkrProgress(id, krs);
            progressPercentage = Math.min(100, totalOkrProgress);
        }

        ProjectStatus newStatus;
        AuditAction action;
        if (progressPercentage < 100) {
            if (request.getReason() == null || request.getReason().trim().isEmpty()) {
                throw new BusinessValidationException("A reason is required to close a project before it is 100% completed.");
            }
            newStatus = ProjectStatus.CLOSED;
            action = AuditAction.PROJECT_CLOSED;
        } else {
            newStatus = ProjectStatus.COMPLETED;
            action = AuditAction.PROJECT_COMPLETED;
        }

        project.setStatus(newStatus);
        project.setClosedAt(LocalDateTime.now());
        project.setCloseReason(request.getReason());
        project.setClosedByAccount(accountRepository.findById(actorId).orElse(null));
        project = projectRepository.save(project);

        String detail = String.format("Project %s. Reason: %s", newStatus, request.getReason() != null ? request.getReason() : "None");
        auditLogService.log(actorId, action, "Project", String.valueOf(project.getId()), detail);

        return toResponse(project, members);
    }

    @Transactional
    public ProjectResponse updateProjectStatus(Long id, UpdateProjectStatusRequest request, Long actorId) {
        Project project = findProjectOrThrow(id);
        validateProjectNotClosedOrCompleted(project);
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
        Project project = findProjectOrThrow(projectId);
        validateProjectNotClosedOrCompleted(project);
        authorizeLeaderOrDeputy(projectId, actorId);


        Account account = resolveMemberAccount(request);
        Long accountId = account.getId();

        if (Boolean.FALSE.equals(account.getIsActive())) {
            throw new BusinessValidationException("Account is disabled: " + account.getEmail());
        }

        if (projectMemberRepository.existsByProject_IdAndAccount_Id(projectId, accountId)) {
            throw new BusinessValidationException("Account " + accountId + " is already a member of project " + projectId);
        }

        ProjectMember member = ProjectMember.builder()
                .project(project)
                .account(account)
                .projectRole(ProjectRole.MEMBER)
                .build();

        member = projectMemberRepository.save(member);
        
        Account sender = actorId != null ? accountRepository.findById(actorId).orElse(null) : null;
        notificationService.notifyProjectMemberAdded(project, account, sender);
        
        if (projectTaskRepository.countByProjectIdAndStatusIn(projectId, java.util.List.of(com.apms.common.enums.TaskStatus.AVAILABLE)) > 0) {
            notificationService.notifyTasksAvailable(project, account, sender);
        }
        
        log.info("Member added: projectId={}, accountId={}, email={}, role=MEMBER", projectId, accountId, account.getEmail());
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
    public void removeMember(Long projectId, Long accountId, Long actorId) {
        Project project = findProjectOrThrow(projectId);
        validateProjectNotClosedOrCompleted(project);

        if (!projectMemberRepository.existsByProject_IdAndAccount_Id(projectId, accountId)) {
            throw new ResourceNotFoundException("Account " + accountId + " is not a member of project " + projectId);
        }

        ProjectMember actor = getProjectMember(projectId, actorId);
        ProjectMember toRemove = projectMemberRepository.findByProject_IdAndAccount_Id(projectId, accountId)
                .orElseThrow();
        
        if (actor.getProjectRole() == ProjectRole.MEMBER) {
            throw new BusinessValidationException("You do not have permission to remove members.");
        }
        if (toRemove.getProjectRole() == ProjectRole.LEADER) {
            throw new BusinessValidationException("The project leader cannot be removed. Transfer leadership first.");
        }
        if (actor.getProjectRole() == ProjectRole.DEPUTY && toRemove.getProjectRole() == ProjectRole.DEPUTY) {
            throw new BusinessValidationException("Deputies cannot remove other deputies.");
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

    @Transactional
    public ProjectMemberResponse updateMemberRole(Long projectId, Long targetAccountId, ProjectRole newRole, Long actorId) {
        Project project = findProjectOrThrow(projectId);
        validateProjectNotClosedOrCompleted(project);
        ProjectMember actor = getProjectMember(projectId, actorId);
        
        if (actor.getProjectRole() != ProjectRole.LEADER) {
            throw new BusinessValidationException("Only the project Leader can perform this action.");
        }
        
        if (newRole == ProjectRole.LEADER) {
            throw new BusinessValidationException("Use the transfer leadership flow to change the project leader.");
        }

        ProjectMember targetMember = projectMemberRepository.findByProject_IdAndAccount_Id(projectId, targetAccountId)
                .orElseThrow(() -> new ResourceNotFoundException("Target member not found in project."));
                
        if (targetMember.getProjectRole() == ProjectRole.LEADER) {
            throw new BusinessValidationException("Cannot change the role of the project leader.");
        }
        
        targetMember.setProjectRole(newRole);
        targetMember = projectMemberRepository.save(targetMember);
        return toMemberResponse(targetMember);
    }

    @Transactional
    public void transferLeadership(Long projectId, Long newLeaderAccountId, boolean leaveProject, Long actorId) {
        Project project = findProjectOrThrow(projectId);
        validateProjectNotClosedOrCompleted(project);
        ProjectMember currentLeader = getProjectMember(projectId, actorId);
        
        if (currentLeader.getProjectRole() != ProjectRole.LEADER) {
            throw new BusinessValidationException("Only the project Leader can transfer leadership.");
        }
        
        if (currentLeader.getAccountId().equals(newLeaderAccountId)) {
            throw new BusinessValidationException("Cannot transfer leadership to yourself.");
        }

        ProjectMember newLeader = projectMemberRepository.findByProject_IdAndAccount_Id(projectId, newLeaderAccountId)
                .orElseThrow(() -> new ResourceNotFoundException("Target member not found in project."));

        newLeader.setProjectRole(ProjectRole.LEADER);
        projectMemberRepository.save(newLeader);

        if (leaveProject) {
            boolean hasActiveTasks = projectTaskRepository.existsByProjectIdAndAssignedToAccountIdAndStatusNotIn(
                    projectId, actorId, List.of(TaskStatus.DONE, TaskStatus.CANCELLED));
            if (hasActiveTasks) {
                throw new BusinessValidationException("Cannot leave project because you have active or in-progress tasks assigned. All assigned tasks must be completed before leaving.");
            }
            projectMemberRepository.deleteByProject_IdAndAccount_Id(projectId, actorId);
        } else {
            currentLeader.setProjectRole(ProjectRole.MEMBER);
            projectMemberRepository.save(currentLeader);
        }
    }

    @Transactional
    public void leaveProject(Long projectId, Long actorId) {
        findProjectOrThrow(projectId);
        ProjectMember member = getProjectMember(projectId, actorId);
        
        if (member.getProjectRole() == ProjectRole.LEADER) {
            long totalMembers = projectMemberRepository.findByProject_Id(projectId).size();
            if (totalMembers <= 1) {
                throw new BusinessValidationException("You must invite another member before leaving the project.");
            }
            throw new BusinessValidationException("The project leader cannot leave directly. Transfer leadership first.");
        }
        
        boolean hasActiveTasks = projectTaskRepository.existsByProjectIdAndAssignedToAccountIdAndStatusNotIn(
                projectId, actorId, List.of(TaskStatus.DONE, TaskStatus.CANCELLED));
        if (hasActiveTasks) {
            throw new BusinessValidationException("Cannot leave project because you have active or in-progress tasks assigned. All assigned tasks must be completed before leaving.");
        }
        
        projectMemberRepository.deleteByProject_IdAndAccount_Id(projectId, actorId);
    }

    private ProjectMember getProjectMember(Long projectId, Long accountId) {
        return projectMemberRepository.findByProject_IdAndAccount_Id(projectId, accountId)
                .orElseThrow(() -> new BusinessValidationException("You are not a member of this project."));
    }

    private void authorizeLeaderOrDeputy(Long projectId, Long actorId) {
        ProjectMember m = getProjectMember(projectId, actorId);
        if (m.getProjectRole() != ProjectRole.LEADER && m.getProjectRole() != ProjectRole.DEPUTY) {
            throw new BusinessValidationException("Only a project Leader or Deputy can perform this action.");
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

    @Transactional
    public String validateAndRepairProjectProfileGovernance(Long projectId, String companyId, Long actorId) {
        Project project = findProjectOrThrow(projectId);

        // 1. Validate authorization: must be LEADER of this project
        ProjectMember member = projectMemberRepository.findByProject_IdAndAccount_Id(projectId, actorId)
                .orElseThrow(() -> new BusinessValidationException("You are not a member of this project"));
        
        if (member.getProjectRole() != ProjectRole.LEADER) {
            throw new BusinessValidationException("Only the Project Leader can govern profile visibility");
        }

        // 2. Validate association
        String resolvedId = projectTargetProfileResolver.resolveTargetProfileId(project);
        String normalizedInputId = projectTargetProfileResolver.normalizeExistingProfileId(companyId).orElse(companyId);

        if (resolvedId == null || !resolvedId.equals(normalizedInputId)) {
            throw new BusinessValidationException("The requested profile does not belong to this project");
        }

        // 3. Repair stale/missing linkage safely if necessary
        if (!normalizedInputId.equals(project.getTargetCompanyProfileId())) {
            project.setTargetCompanyProfileId(normalizedInputId);
            projectRepository.save(project);
        }
        
        return normalizedInputId;
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
        if (project.getStatus() == com.apms.common.enums.ProjectStatus.ACTIVE && project.getPlannedEndDate() != null && progressPercentage < 100) {
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
                .filter(m -> m.getProjectRole() == com.apms.common.enums.ProjectRole.LEADER)
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
            if (project.getStatus() == com.apms.common.enums.ProjectStatus.ACTIVE && project.getPlannedEndDate() != null && progressPercentage < 100) {
                if (LocalDate.now().isAfter(project.getPlannedEndDate())) {
                    isOverdue = true;
                }
            }
        }

        String targetProfileId = project.getTargetCompanyProfileId();
        if (!org.springframework.util.StringUtils.hasText(targetProfileId) && projectTargetProfileResolver != null) {
            targetProfileId = projectTargetProfileResolver.resolveTargetProfileId(project);
        }

        return ProjectResponse.builder()
                .id(project.getId())
                .projectName(project.getProjectName())
                .projectType(project.getProjectType())
                .targetCompanyProfileId(targetProfileId)
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
                .closedAt(project.getClosedAt())
                .closeReason(project.getCloseReason())
                .closedBy(project.getClosedByAccount() != null ? project.getClosedByAccount().getId() : null)
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
                .accountRole(account != null && account.getRoles() != null && !account.getRoles().isEmpty() ? account.getRoles().iterator().next() : null)
                .projectRole(m.getProjectRole())
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

    private void syncTargetIdentity(String profileId, String targetTaxCode) {
        if (!org.springframework.util.StringUtils.hasText(targetTaxCode)) {
            return;
        }
        java.util.Optional<com.apms.domain.profile.CompanyProfile> profileOpt = companyProfileRepository.findByCompanyId(profileId)
            .or(() -> companyProfileRepository.findById(profileId));
            
        if (profileOpt.isPresent()) {
            com.apms.domain.profile.CompanyProfile profile = profileOpt.get();
            if (profile.getIdentity() == null) {
                profile.setIdentity(new com.apms.domain.profile.CompanyProfile.Identity());
            }
            String existingTaxCode = profile.getIdentity().getTaxCode();
            if (!org.springframework.util.StringUtils.hasText(existingTaxCode)) {
                profile.getIdentity().setTaxCode(targetTaxCode);
                companyProfileRepository.save(profile);
            } else if (!existingTaxCode.trim().equalsIgnoreCase(targetTaxCode.trim())) {
                throw new com.apms.common.exception.BusinessValidationException("TAX_CODE_CONFLICT", "Project tax code (" + targetTaxCode + ") conflicts with existing profile tax code (" + existingTaxCode + "). Please review company identity.");
            }
        }
    }

    @Transactional(readOnly = true)
    public List<com.apms.domain.dashboard.dto.ManagerReviewHistoryItemResponse> getProjectReviewHistory(Long projectId) {
        if (projectId == null) {
            return java.util.Collections.emptyList();
        }

        List<com.apms.domain.project.ProjectTaskSubmission> submissions = projectTaskSubmissionRepository.findReviewHistoryByProjectId(projectId);
        List<com.apms.domain.dashboard.dto.ManagerReviewHistoryItemResponse> items = new ArrayList<>();
        java.util.Set<String> processedCandidateIds = new java.util.HashSet<>();

        for (com.apms.domain.project.ProjectTaskSubmission s : submissions) {
            com.apms.domain.project.ProjectTask task = s.getProjectTask();
            Project project = s.getProject();
            Account submitter = s.getSubmittedByAccount();
            Account reviewer = s.getReviewedByAccount();

            String targetName = null;
            if (StringUtils.hasText(s.getTargetEntityId())) {
                if ("CompanyCandidate".equals(s.getTargetEntityType())) {
                    processedCandidateIds.add(s.getTargetEntityId());
                    if (companyCandidateRepository != null) {
                        try {
                            java.util.Optional<com.apms.domain.candidate.CompanyCandidate> candOpt =
                                    companyCandidateRepository.findById(s.getTargetEntityId());
                            if (candOpt.isPresent()) {
                                com.apms.domain.candidate.CompanyCandidate cand = candOpt.get();
                                if (cand.getIdentity() != null && StringUtils.hasText(cand.getIdentity().getLegalName())) {
                                    targetName = cand.getIdentity().getLegalName();
                                } else if (StringUtils.hasText(cand.getDraftName())) {
                                    targetName = cand.getDraftName();
                                }
                            }
                        } catch (Exception e) {
                            log.debug("Could not lookup candidate name for submission {}: {}", s.getId(), e.getMessage());
                        }
                    }
                }
            }

            String comment = s.getReviewComment();
            LocalDateTime reviewedAt = s.getReviewedAt();
            String reviewerName = reviewer != null ? reviewer.getEmail() : null;

            // 1. Fallback / Enrichment for Financial Research
            boolean isFinancial = s.getSubmissionType() == com.apms.common.enums.SubmissionType.FINANCIAL_RESEARCH
                    || (task != null && task.getTaskType() == com.apms.common.enums.TaskType.FINANCIAL_RESEARCH);
            if (financialResearchRepository != null && task != null && isFinancial) {
                try {
                    var frOpt = financialResearchRepository.findByTaskId(task.getId());
                    if (frOpt.isPresent()) {
                        var fr = frOpt.get();
                        if (fr.getReports() != null) {
                            var matchedReport = fr.getReports().stream()
                                    .filter(r -> r.getReviewStatus() == com.apms.domain.financial.FinancialReportReviewStatus.CHANGES_REQUESTED
                                            || (StringUtils.hasText(r.getReviewComment()) && r.getReviewedAt() != null))
                                    .sorted((a, b) -> {
                                        if (a.getReviewedAt() != null && b.getReviewedAt() != null) {
                                            return b.getReviewedAt().compareTo(a.getReviewedAt());
                                        }
                                        return 0;
                                    })
                                    .findFirst()
                                    .orElse(null);
                            if (matchedReport != null) {
                                if (!StringUtils.hasText(comment)) {
                                    comment = matchedReport.getReviewComment();
                                }
                                if (reviewedAt == null) {
                                    reviewedAt = matchedReport.getReviewedAt();
                                }
                                if (!StringUtils.hasText(reviewerName)) {
                                    reviewerName = matchedReport.getReviewedByName();
                                }
                                if (!StringUtils.hasText(targetName)) {
                                    targetName = matchedReport.getTitle();
                                }
                            }
                        }
                        if (reviewedAt == null) {
                            reviewedAt = fr.getReviewedAt();
                        }
                        if (!StringUtils.hasText(comment) && StringUtils.hasText(fr.getReviewReason())) {
                            comment = fr.getReviewReason();
                        }
                    }
                } catch (Exception e) {
                    log.debug("Could not enrich financial review history: {}", e.getMessage());
                }
            }

            // 2. Fallback / Enrichment for Contract Research
            boolean isContract = s.getSubmissionType() == com.apms.common.enums.SubmissionType.PARTNER_CONTRACT_COLLECTION
                    || (task != null && task.getTaskType() == com.apms.common.enums.TaskType.PARTNER_CONTRACT_COLLECTION);
            if (contractResearchRepository != null && task != null && isContract) {
                try {
                    var crOpt = contractResearchRepository.findByTaskId(task.getId());
                    if (crOpt.isPresent()) {
                        var cr = crOpt.get();
                        if (cr.getContracts() != null) {
                            var matchedContract = cr.getContracts().stream()
                                    .filter(c -> c.getReviewStatus() == com.apms.domain.contract.enums.ContractEntryReviewStatus.CHANGES_REQUESTED
                                            || (StringUtils.hasText(c.getReviewComment()) && c.getReviewedAt() != null))
                                    .sorted((a, b) -> {
                                        if (a.getReviewedAt() != null && b.getReviewedAt() != null) {
                                            return b.getReviewedAt().compareTo(a.getReviewedAt());
                                        }
                                        return 0;
                                    })
                                    .findFirst()
                                    .orElse(null);
                            if (matchedContract != null) {
                                if (!StringUtils.hasText(comment)) {
                                    comment = matchedContract.getReviewComment();
                                }
                                if (reviewedAt == null) {
                                    reviewedAt = matchedContract.getReviewedAt();
                                }
                                if (!StringUtils.hasText(reviewerName)) {
                                    reviewerName = matchedContract.getReviewedByName();
                                }
                                if (!StringUtils.hasText(targetName)) {
                                    targetName = matchedContract.getTitle() != null ? matchedContract.getTitle() : matchedContract.getDocumentName();
                                }
                            }
                        }
                        if (reviewedAt == null) {
                            reviewedAt = cr.getReviewedAt();
                        }
                    }
                } catch (Exception e) {
                    log.debug("Could not enrich contract review history: {}", e.getMessage());
                }
            }

            if (!StringUtils.hasText(targetName) && project != null && StringUtils.hasText(project.getTargetCompanyName())) {
                targetName = project.getTargetCompanyName();
            }

            // 3. Resolve human-readable Display Names
            String submitterDisplayName = null;
            if (submitter != null) {
                submitterDisplayName = userProfileRepository.findByAccountId(submitter.getId())
                        .map(p -> ((p.getFirstName() != null ? p.getFirstName() : "") + " " + (p.getLastName() != null ? p.getLastName() : "")).trim())
                        .filter(StringUtils::hasText)
                        .orElse(submitter.getEmail());
            }

            String reviewerDisplayName = null;
            if (reviewer != null) {
                reviewerDisplayName = userProfileRepository.findByAccountId(reviewer.getId())
                        .map(p -> ((p.getFirstName() != null ? p.getFirstName() : "") + " " + (p.getLastName() != null ? p.getLastName() : "")).trim())
                        .filter(StringUtils::hasText)
                        .orElse(reviewer.getEmail());
            } else if (StringUtils.hasText(reviewerName)) {
                reviewerDisplayName = reviewerName;
            } else if (reviewedAt != null) {
                reviewerDisplayName = "Manager";
            }

            items.add(com.apms.domain.dashboard.dto.ManagerReviewHistoryItemResponse.builder()
                    .submissionId(s.getId())
                    .projectId(project != null ? project.getId() : projectId)
                    .projectName(project != null ? project.getProjectName() : null)
                    .targetCompanyName(project != null ? project.getTargetCompanyName() : null)
                    .taskId(task != null ? task.getId() : null)
                    .taskTitle(task != null ? task.getTitle() : "Task")
                    .taskType(task != null ? task.getTaskType() : null)
                    .submissionType(s.getSubmissionType())
                    .targetEntityType(s.getTargetEntityType())
                    .targetEntityId(s.getTargetEntityId())
                    .targetEntityName(targetName)
                    .submittedRevisionNumber(s.getSubmittedRevisionNumber())
                    .submittedByUserId(submitter != null ? submitter.getId() : null)
                    .submittedByName(submitterDisplayName != null ? submitterDisplayName : (submitter != null ? submitter.getEmail() : null))
                    .submittedAt(s.getSubmittedAt())
                    .status(s.getStatus())
                    .reviewedByUserId(reviewer != null ? reviewer.getId() : null)
                    .reviewedByName(reviewerDisplayName)
                    .reviewedAt(reviewedAt)
                    .reviewComment(comment)
                    .note(s.getNote())
                    .build());
        }

        // Also incorporate any CompanyCandidate from Mongo that has review activity not represented in submissions
        if (companyCandidateRepository != null) {
            try {
                List<com.apms.domain.candidate.CompanyCandidate> candidates =
                        companyCandidateRepository.findByProjectId(String.valueOf(projectId), org.springframework.data.domain.Pageable.unpaged()).getContent();
                for (com.apms.domain.candidate.CompanyCandidate c : candidates) {
                    if (c.getId() != null && !processedCandidateIds.contains(c.getId())) {
                        boolean hasReviewActivity = c.getStatus() == com.apms.common.enums.CandidateStatus.APPROVED
                                || c.getStatus() == com.apms.common.enums.CandidateStatus.REVISION_REQUIRED
                                || c.getStatus() == com.apms.common.enums.CandidateStatus.REJECTED
                                || c.getStatus() == com.apms.common.enums.CandidateStatus.PENDING_REVIEW;
                        if (hasReviewActivity) {
                            com.apms.common.enums.SubmissionStatus mappedStatus = switch (c.getStatus()) {
                                case APPROVED -> com.apms.common.enums.SubmissionStatus.APPROVED;
                                case REVISION_REQUIRED -> com.apms.common.enums.SubmissionStatus.CHANGES_REQUESTED;
                                case REJECTED -> com.apms.common.enums.SubmissionStatus.REJECTED;
                                case PENDING_REVIEW -> com.apms.common.enums.SubmissionStatus.IN_REVIEW;
                                default -> com.apms.common.enums.SubmissionStatus.DRAFT;
                            };

                            String candName = c.getIdentity() != null && StringUtils.hasText(c.getIdentity().getLegalName())
                                    ? c.getIdentity().getLegalName()
                                    : (StringUtils.hasText(c.getDraftName()) ? c.getDraftName() : "Candidate #" + (c.getCandidateOrder() != null ? c.getCandidateOrder() : c.getId().substring(Math.max(0, c.getId().length() - 6))));

                            LocalDateTime reviewedDate = null;
                            String revComment = null;
                            String revByName = null;
                            if (c.getReview() != null) {
                                reviewedDate = c.getReview().getReviewedAt();
                                revComment = c.getReview().getRejectionReason();
                                revByName = c.getReview().getReviewedBy();
                            }
                            if (reviewedDate == null && c.getStatus() == com.apms.common.enums.CandidateStatus.APPROVED) {
                                reviewedDate = c.getLastSubmittedAt();
                            }

                            items.add(com.apms.domain.dashboard.dto.ManagerReviewHistoryItemResponse.builder()
                                    .submissionId(null)
                                    .projectId(projectId)
                                    .taskId(c.getTaskId())
                                    .taskTitle("Basic Company Information")
                                    .taskType(com.apms.common.enums.TaskType.COMPANY_DATA_PREPARATION)
                                    .submissionType(com.apms.common.enums.SubmissionType.COMPANY_CANDIDATE)
                                    .targetEntityType("CompanyCandidate")
                                    .targetEntityId(c.getId())
                                    .targetEntityName(candName)
                                    .submittedRevisionNumber(c.getRevisionNumber())
                                    .submittedAt(c.getLastSubmittedAt())
                                    .status(mappedStatus)
                                    .reviewedByName(revByName != null ? revByName : (reviewedDate != null ? "Manager" : null))
                                    .reviewedAt(reviewedDate)
                                    .reviewComment(revComment)
                                    .build());
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Could not check legacy candidates for project {}: {}", projectId, e.getMessage());
            }
        }

        items.sort((a, b) -> {
            LocalDateTime timeA = a.getReviewedAt() != null ? a.getReviewedAt() : a.getSubmittedAt();
            LocalDateTime timeB = b.getReviewedAt() != null ? b.getReviewedAt() : b.getSubmittedAt();
            if (timeA == null && timeB == null) return 0;
            if (timeA == null) return 1;
            if (timeB == null) return -1;
            return timeB.compareTo(timeA);
        });

        return items;
    }
}
