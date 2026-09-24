package com.apms.domain.project.service;

import com.apms.common.enums.*;
import com.apms.common.exception.BusinessValidationException;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.candidate.repository.mongo.CompanyCandidateRepository;
import com.apms.domain.contract.repository.mongo.ContractResearchRepository;
import com.apms.domain.financial.repository.FinancialResearchRepository;
import com.apms.domain.graph.service.GraphService;
import com.apms.domain.monitoring.repository.CompanyMonitoringAssignmentRepository;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.profile.repository.mongo.CompanyProfileVersionRepository;
import com.apms.domain.profile.service.CompanyProfileOfficialEvaluator;
import com.apms.domain.profile.service.CompanyProfileVersionService;
import com.apms.domain.profile.service.OwnerOrganizationService;
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
import org.springframework.beans.factory.annotation.Autowired;
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
import com.apms.domain.project.dto.UpdateProjectStatusRequest;
import com.apms.security.UserDetailsImpl;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
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
    private final OwnerOrganizationService ownerOrganizationService;
    private final NotificationService notificationService;
    private final CompanyProfileRepository companyProfileRepository;
    private final ProjectTargetProfileResolver projectTargetProfileResolver;
    private final CompanyProfileOfficialEvaluator companyProfileOfficialEvaluator;

    @Autowired(required = false)
    private CompanyCandidateRepository companyCandidateRepository;

    @Autowired(required = false)
    private FinancialResearchRepository financialResearchRepository;

    @Autowired(required = false)
    private ContractResearchRepository contractResearchRepository;

    @Autowired(required = false)
    private GraphService graphService;

    @Autowired(required = false)
    private CompanyProfileVersionService companyProfileVersionService;

    @Autowired(required = false)
    private CompanyProfileVersionRepository companyProfileVersionRepository;

    @Autowired(required = false)
    private CompanyMonitoringAssignmentRepository companyMonitoringAssignmentRepository;

    // ─────────────────────────────────────────────
    // CREATE
    // ─────────────────────────────────────────────

    @Transactional
    public ProjectResponse createProject(CreateProjectRequest request, Long creatorAccountId) {
        RelationshipType resolvedRelationshipType = request.getTargetRelationshipType();

        if (request.getProjectType() == ProjectType.UPDATE_EXISTING_COMPANY && resolvedRelationshipType == null) {
            resolvedRelationshipType = resolveCanonicalRelationship(request.getTargetCompanyProfileId());
            if (resolvedRelationshipType == null) {
                throw new BusinessValidationException("No existing relationship found for this company. Please provide targetRelationshipType.");
            }
        }

        if (request.getProjectType() == ProjectType.UPDATE_EXISTING_COMPANY) {
            String targetProfileId = request.getTargetCompanyProfileId();
            if (!StringUtils.hasText(targetProfileId)) {
                throw new BusinessValidationException("Target company profile ID is required for UPDATE_EXISTING_COMPANY");
            }

            // Reload CompanyProfile immediately before authorization
            CompanyProfile profile = companyProfileRepository.findByCompanyId(targetProfileId)
                    .or(() -> companyProfileRepository.findById(targetProfileId))
                    .orElseThrow(() -> new ResourceNotFoundException("Target company profile not found: " + targetProfileId));

            Account creator = accountRepository.findById(creatorAccountId)
                    .orElseThrow(() -> new ResourceNotFoundException("Creator account not found: " + creatorAccountId));

            boolean isAdminOrOwner = creator.getRoles().stream()
                    .anyMatch(r -> r == SystemRole.SYSTEM_ADMIN || r == SystemRole.BUSINESS_OWNER);

            if (!isAdminOrOwner) {
                // If responsibleManagerId is null or does not match creator, deny access (403)
                if (profile.getResponsibleManagerId() == null || !profile.getResponsibleManagerId().equals(creatorAccountId)) {
                    throw new org.springframework.security.access.AccessDeniedException(
                            "You are not the responsible manager for this company profile. Only the assigned manager can create projects for it.");
                }
            }

            OpenProjectCheckResponse openCheck = checkOpenProjectForCompany(
                    request.getTargetCompanyProfileId(),
                    request.getTargetCompanyTaxCode(),
                    null
            );
            if (openCheck.isHasOpenProject()) {
                Map<String, Object> details = new LinkedHashMap<>();
                if (openCheck.getProjectId() != null) details.put("conflictingProjectId", openCheck.getProjectId());
                if (openCheck.getProjectName() != null) details.put("conflictingProjectName", openCheck.getProjectName());
                if (openCheck.getStatus() != null) details.put("conflictingProjectStatus", openCheck.getStatus());
                throw new BusinessValidationException(
                        "COMPANY_HAS_OPEN_PROJECT",
                        "An unfinished project already exists for this company. Complete or close the existing project before creating another one.",
                        details
                );
            }
        }

        if (request.getProjectType() == ProjectType.RESEARCH_NEW_COMPANY) {
            if (StringUtils.hasText(request.getTargetCompanyTaxCode())) {
                OpenProjectCheckResponse openCheck = checkOpenProjectForCompany(
                        null,
                        request.getTargetCompanyTaxCode(),
                        null
                );
                if (openCheck.isHasOpenProject()) {
                    java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
                    if (openCheck.getProjectId() != null) details.put("conflictingProjectId", openCheck.getProjectId());
                    if (openCheck.getProjectName() != null) details.put("conflictingProjectName", openCheck.getProjectName());
                    if (openCheck.getStatus() != null) details.put("conflictingProjectStatus", openCheck.getStatus());
                    throw new BusinessValidationException(
                            "COMPANY_HAS_OPEN_PROJECT",
                            "An unfinished project already exists for this company. Complete or close the existing project before creating another one.",
                            details
                    );
                }
                DuplicateTaxCodeCheckResponse duplicateCheck = checkDuplicateTaxCode(request.getTargetCompanyTaxCode());
                if (duplicateCheck.isExists()) {
                    if (duplicateCheck.isExistingOfficialCompany() || "COMPANY_PROFILE".equals(duplicateCheck.getMatchType())) {
                        throw new BusinessValidationException("An official company already exists with this tax code. Please select the 'Update existing company' project type.");
                    } else if (duplicateCheck.isOpenResearchProject() || duplicateCheck.isHasOpenProject() || "OPEN_RESEARCH_PROJECT".equals(duplicateCheck.getMatchType()) || "ACTIVE_PROJECT".equals(duplicateCheck.getMatchType())) {
                        java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
                        if (duplicateCheck.getOpenProjectId() != null) details.put("conflictingProjectId", duplicateCheck.getOpenProjectId());
                        if (duplicateCheck.getOpenProjectName() != null) details.put("conflictingProjectName", duplicateCheck.getOpenProjectName());
                        if (duplicateCheck.getOpenProjectStatus() != null) details.put("conflictingProjectStatus", duplicateCheck.getOpenProjectStatus());
                        throw new BusinessValidationException(
                                "COMPANY_HAS_OPEN_PROJECT",
                                "A research project already exists for this tax code. Complete or close the existing project before creating another one.",
                                details
                        );
                    } else {
                        throw new BusinessValidationException("Duplicate tax code found: " + duplicateCheck.getMatchType());
                    }
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

        validateKeyResults(request.getKeyResults(), request.getProjectType(), request.getTargetCompanyProfileId(), resolvedRelationshipType);

        String targetTaxCode = request.getTargetCompanyTaxCode();
        final String createTargetProfileId = request.getTargetCompanyProfileId();
        if (request.getProjectType() == ProjectType.UPDATE_EXISTING_COMPANY && org.springframework.util.StringUtils.hasText(createTargetProfileId)) {
            java.util.Optional<com.apms.domain.profile.CompanyProfile> profileOpt = companyProfileRepository.findByCompanyId(createTargetProfileId)
                    .or(() -> companyProfileRepository.findById(createTargetProfileId));
            if (profileOpt.isPresent()) {
                com.apms.domain.profile.CompanyProfile profile = profileOpt.get();
                if (profile.getIdentity() != null && org.springframework.util.StringUtils.hasText(profile.getIdentity().getTaxCode())) {
                    targetTaxCode = profile.getIdentity().getTaxCode();
                }
            }
        }

        RelationshipType originalRel = null;
        if (request.getProjectType() == ProjectType.UPDATE_EXISTING_COMPANY && org.springframework.util.StringUtils.hasText(createTargetProfileId)) {
            originalRel = resolveCanonicalRelationship(createTargetProfileId);
        }

        Project project = Project.builder()
                .projectName(request.getProjectName())
                .projectType(request.getProjectType())
                .targetCompanyProfileId(request.getTargetCompanyProfileId())
                .targetCompanyName(request.getTargetCompanyName())
                .targetCompanyTaxCode(targetTaxCode)
                .targetRelationshipType(resolvedRelationshipType)
                .originalRelationshipType(originalRel)
                .description(request.getDescription())
                .objective(request.getObjective())
                .plannedEndDate(request.getPlannedEndDate())
                .status(ProjectStatus.DRAFT)
                .createdByAccount(accountRepository.getReferenceById(creatorAccountId))
                .build();

        project = projectRepository.save(project);
        
        if (project.getProjectType() == ProjectType.RESEARCH_NEW_COMPANY && projectTargetProfileResolver != null) {
            CompanyProfile shell = projectTargetProfileResolver.getOrCreateProjectProfileShell(project, creatorAccountId);
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
        if (project.getProjectType() == ProjectType.RESEARCH_NEW_COMPANY && !StringUtils.hasText(project.getTargetCompanyProfileId()) && projectTargetProfileResolver != null) {
            com.apms.domain.profile.CompanyProfile shell = projectTargetProfileResolver.getOrCreateProjectProfileShell(project, project.getCreatedById());
            if (shell != null && !shell.getCompanyId().equals(project.getTargetCompanyProfileId())) {
                project.setTargetCompanyProfileId(shell.getCompanyId());
                project = projectRepository.save(project);
            }
        }
        final String lookupProfileId = project.getTargetCompanyProfileId();
        if (!StringUtils.hasText(project.getTargetCompanyTaxCode()) && StringUtils.hasText(lookupProfileId)) {
            Optional<CompanyProfile> profileOpt = companyProfileRepository.findByCompanyId(lookupProfileId)
                    .or(() -> companyProfileRepository.findById(lookupProfileId));
            if (profileOpt.isPresent() && profileOpt.get().getIdentity() != null && StringUtils.hasText(profileOpt.get().getIdentity().getTaxCode())) {
                project.setTargetCompanyTaxCode(profileOpt.get().getIdentity().getTaxCode());
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
        Map<Long, ProjectTaskRepository.ProjectTaskStats> statsMap = projectIds.isEmpty() ? Collections.emptyMap() :
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
            if (request.getTargetCompanyProfileId() != null && !request.getTargetCompanyProfileId().equals(project.getTargetCompanyProfileId())) {
                com.apms.domain.project.dto.OpenProjectCheckResponse openCheck = checkOpenProjectForCompany(
                        request.getTargetCompanyProfileId(),
                        request.getTargetCompanyTaxCode() != null ? request.getTargetCompanyTaxCode() : project.getTargetCompanyTaxCode(),
                        project.getId()
                );
                if (openCheck.isHasOpenProject()) {
                    java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
                    if (openCheck.getProjectId() != null) details.put("conflictingProjectId", openCheck.getProjectId());
                    if (openCheck.getProjectName() != null) details.put("conflictingProjectName", openCheck.getProjectName());
                    if (openCheck.getStatus() != null) details.put("conflictingProjectStatus", openCheck.getStatus());
                    throw new BusinessValidationException(
                            "COMPANY_HAS_OPEN_PROJECT",
                            "An unfinished project already exists for this company. Complete or close the existing project before creating another one.",
                            details
                    );
                }
                project.setTargetCompanyProfileId(request.getTargetCompanyProfileId());
            }
            if (request.getTargetCompanyTaxCode() != null) {
                if (org.springframework.util.StringUtils.hasText(request.getTargetCompanyTaxCode()) && 
                    !request.getTargetCompanyTaxCode().equals(project.getTargetCompanyTaxCode())) {
                    if (project.getProjectType() == ProjectType.RESEARCH_NEW_COMPANY) {
                        com.apms.domain.project.dto.OpenProjectCheckResponse openCheck = checkOpenProjectForCompany(
                                null,
                                request.getTargetCompanyTaxCode(),
                                project.getId()
                        );
                        if (openCheck.isHasOpenProject()) {
                            java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
                            if (openCheck.getProjectId() != null) details.put("conflictingProjectId", openCheck.getProjectId());
                            if (openCheck.getProjectName() != null) details.put("conflictingProjectName", openCheck.getProjectName());
                            if (openCheck.getStatus() != null) details.put("conflictingProjectStatus", openCheck.getStatus());
                            throw new BusinessValidationException(
                                    "COMPANY_HAS_OPEN_PROJECT",
                                    "An unfinished project already exists for this company. Complete or close the existing project before creating another one.",
                                    details
                            );
                        }
                        com.apms.domain.project.dto.DuplicateTaxCodeCheckResponse duplicateCheck = checkDuplicateTaxCode(request.getTargetCompanyTaxCode());
                        if (duplicateCheck.isExists()) {
                            if (duplicateCheck.isOpenResearchProject() || duplicateCheck.isHasOpenProject()) {
                                java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
                                if (duplicateCheck.getOpenProjectId() != null) details.put("conflictingProjectId", duplicateCheck.getOpenProjectId());
                                if (duplicateCheck.getOpenProjectName() != null) details.put("conflictingProjectName", duplicateCheck.getOpenProjectName());
                                if (duplicateCheck.getOpenProjectStatus() != null) details.put("conflictingProjectStatus", duplicateCheck.getOpenProjectStatus());
                                throw new BusinessValidationException(
                                        "COMPANY_HAS_OPEN_PROJECT",
                                        "An unfinished project already exists for this tax code. Complete or close the existing project before creating another one.",
                                        details
                                );
                            }
                        }
                    }
                }
                project.setTargetCompanyTaxCode(request.getTargetCompanyTaxCode());
            } else if (project.getProjectType() == ProjectType.UPDATE_EXISTING_COMPANY && org.springframework.util.StringUtils.hasText(project.getTargetCompanyProfileId())) {
                final String existingProfileId = project.getTargetCompanyProfileId();
                java.util.Optional<com.apms.domain.profile.CompanyProfile> profileOpt = companyProfileRepository.findByCompanyId(existingProfileId)
                        .or(() -> companyProfileRepository.findById(existingProfileId));
                if (profileOpt.isPresent() && profileOpt.get().getIdentity() != null && org.springframework.util.StringUtils.hasText(profileOpt.get().getIdentity().getTaxCode())) {
                    project.setTargetCompanyTaxCode(profileOpt.get().getIdentity().getTaxCode());
                }
            }
            
            if (StringUtils.hasText(project.getTargetCompanyProfileId())) {
                syncTargetIdentity(project.getTargetCompanyProfileId(), project.getTargetCompanyTaxCode());
            }

            if (request.getKeyResults() != null && !request.getKeyResults().isEmpty()) {
                validateKeyResults(request.getKeyResults(), project.getProjectType(), project.getTargetCompanyProfileId(), project.getTargetRelationshipType());

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

        if (newStatus == ProjectStatus.COMPLETED) {
            applyTargetRelationshipOnCompletion(project, actorId);
        }

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

        if (newStatus == ProjectStatus.COMPLETED) {
            applyTargetRelationshipOnCompletion(project, actorId);
        }

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

        if (project.getStatus() != ProjectStatus.DRAFT) {
            throw new BusinessValidationException(
                    "PROJECT_NOT_DRAFT",
                    "Only draft projects can be deleted.");
        }

        String projectName = project.getProjectName();
        ProjectStatus status = project.getStatus();
        ProjectType projectType = project.getProjectType();

        // 1. Resolve linked CompanyProfile for NEW_COMPANY_RESEARCH
        com.apms.domain.profile.CompanyProfile linkedProfile = null;
        if (projectType == ProjectType.RESEARCH_NEW_COMPANY) {
            if (org.springframework.util.StringUtils.hasText(project.getTargetCompanyProfileId())) {
                String profileId = project.getTargetCompanyProfileId().trim();
                linkedProfile = companyProfileRepository.findByCompanyId(profileId)
                        .or(() -> companyProfileRepository.findById(profileId))
                        .orElse(null);
            }
            if (linkedProfile == null && project.getId() != null) {
                java.util.List<com.apms.domain.profile.CompanyProfile> byProj = companyProfileRepository.findByProjectId(String.valueOf(project.getId()));
                if (!byProj.isEmpty()) {
                    linkedProfile = byProj.get(0);
                }
            }
            if (linkedProfile == null && org.springframework.util.StringUtils.hasText(project.getTargetCompanyTaxCode())) {
                String normTax = project.getTargetCompanyTaxCode().replaceAll("[\\s\\-]", "").trim();
                linkedProfile = companyProfileRepository.findByIdentityTaxCode(normTax).orElse(null);
            }
        }

        // 2. Clean up project-owned dependencies
        projectTaskSubmissionRepository.deleteByProject_Id(id);
        projectTaskRepository.deleteByProjectId(id);
        projectKeyResultRepository.deleteByProjectId(id);
        importJobRepository.deleteByProject_Id(id);
        projectMemberRepository.deleteByProject_Id(id);

        // 3. Handle linked CompanyProfile shell cleanup if applicable
        if (linkedProfile != null && projectType == ProjectType.RESEARCH_NEW_COMPANY) {
            String projectIdStr = String.valueOf(project.getId());
            if (linkedProfile.getSourceRefs() != null && linkedProfile.getSourceRefs().getProjectIds() != null) {
                linkedProfile.getSourceRefs().getProjectIds().remove(projectIdStr);
            }

            boolean isUnverified = linkedProfile.getReviewStatus() == null ||
                    "UNVERIFIED".equalsIgnoreCase(linkedProfile.getReviewStatus());

            boolean hasRemainingSourceProjectIds = linkedProfile.getSourceRefs() != null &&
                    linkedProfile.getSourceRefs().getProjectIds() != null &&
                    !linkedProfile.getSourceRefs().getProjectIds().isEmpty();

            boolean hasSourceCandidates = linkedProfile.getSourceRefs() != null &&
                    linkedProfile.getSourceRefs().getCandidateIds() != null &&
                    !linkedProfile.getSourceRefs().getCandidateIds().isEmpty();

            boolean hasSourceImportJobs = linkedProfile.getSourceRefs() != null &&
                    linkedProfile.getSourceRefs().getImportJobIds() != null &&
                    !linkedProfile.getSourceRefs().getImportJobIds().isEmpty();

            boolean hasSourceDocs = linkedProfile.getSourceRefs() != null &&
                    linkedProfile.getSourceRefs().getRawDocumentIds() != null &&
                    !linkedProfile.getSourceRefs().getRawDocumentIds().isEmpty();

            java.util.List<String> profileIdsToCheck = new java.util.ArrayList<>();
            if (org.springframework.util.StringUtils.hasText(linkedProfile.getCompanyId())) {
                profileIdsToCheck.add(linkedProfile.getCompanyId());
            }
            if (org.springframework.util.StringUtils.hasText(linkedProfile.getId()) && !profileIdsToCheck.contains(linkedProfile.getId())) {
                profileIdsToCheck.add(linkedProfile.getId());
            }

            boolean hasOtherProjectInDb = false;
            if (!profileIdsToCheck.isEmpty()) {
                hasOtherProjectInDb = projectRepository.existsByTargetCompanyProfileIdInAndIdNot(profileIdsToCheck, project.getId());
            }

            boolean hasMonitoring = false;
            if (companyMonitoringAssignmentRepository != null) {
                if (org.springframework.util.StringUtils.hasText(linkedProfile.getCompanyId()) &&
                        companyMonitoringAssignmentRepository.findByCompanyProfileId(linkedProfile.getCompanyId()).isPresent()) {
                    hasMonitoring = true;
                } else if (org.springframework.util.StringUtils.hasText(linkedProfile.getId()) &&
                        companyMonitoringAssignmentRepository.findByCompanyProfileId(linkedProfile.getId()).isPresent()) {
                    hasMonitoring = true;
                }
            }

            boolean hasVersionHistory = false;
            if (companyProfileVersionRepository != null) {
                if (org.springframework.util.StringUtils.hasText(linkedProfile.getId()) &&
                        companyProfileVersionRepository.existsByCompanyProfileId(linkedProfile.getId())) {
                    hasVersionHistory = true;
                } else if (org.springframework.util.StringUtils.hasText(linkedProfile.getCompanyId()) &&
                        companyProfileVersionRepository.existsByCompanyProfileId(linkedProfile.getCompanyId())) {
                    hasVersionHistory = true;
                }
            }

            boolean isOrphanShell = isUnverified
                    && !hasRemainingSourceProjectIds
                    && !hasSourceCandidates
                    && !hasSourceImportJobs
                    && !hasSourceDocs
                    && !hasOtherProjectInDb
                    && !hasMonitoring
                    && !hasVersionHistory
                    && (linkedProfile.getFinancial() == null)
                    && (linkedProfile.getMarket() == null)
                    && (linkedProfile.getCompanyMembers() == null || linkedProfile.getCompanyMembers().isEmpty());

            if (isOrphanShell) {
                companyProfileRepository.delete(linkedProfile);
                log.info("Deleted orphan company profile shell: id={}, companyId={}, taxCode={}",
                        linkedProfile.getId(), linkedProfile.getCompanyId(),
                        linkedProfile.getIdentity() != null ? linkedProfile.getIdentity().getTaxCode() : "N/A");
            } else {
                companyProfileRepository.save(linkedProfile);
                log.info("Preserved company profile, removed project reference {}: id={}, companyId={}",
                        project.getId(), linkedProfile.getId(), linkedProfile.getCompanyId());
            }
        }

        // 4. Delete project entity
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

        boolean isEligibleStaff = account.getRoles() != null &&
                account.getRoles().contains(SystemRole.BUSINESS_DEVELOPMENT_STAFF) &&
                !account.getRoles().contains(SystemRole.SYSTEM_ADMIN) &&
                !account.getRoles().contains(SystemRole.BUSINESS_OWNER) &&
                !account.getRoles().contains(SystemRole.BUSINESS_DEVELOPMENT_MANAGER);

        if (!isEligibleStaff) {
            throw new BusinessValidationException("Only accounts with role BUSINESS_DEVELOPMENT_STAFF can be added as project members: " + account.getEmail());
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
    private RelationshipType resolveCanonicalRelationship(String targetCompanyProfileId) {
        if (!StringUtils.hasText(targetCompanyProfileId)) {
            return null;
        }

        List<String> targetIds = new ArrayList<>();
        targetIds.add(targetCompanyProfileId.trim());

        Optional<CompanyProfile> profileOpt = companyProfileRepository.findByCompanyId(targetCompanyProfileId)
                .or(() -> companyProfileRepository.findById(targetCompanyProfileId));
        if (profileOpt.isPresent()) {
            CompanyProfile p = profileOpt.get();
            if (StringUtils.hasText(p.getCompanyId()) && !targetIds.contains(p.getCompanyId().trim())) {
                targetIds.add(p.getCompanyId().trim());
            }
            if (StringUtils.hasText(p.getId()) && !targetIds.contains(p.getId().trim())) {
                targetIds.add(p.getId().trim());
            }
        }

        try {
            String ownerCompanyId = ownerOrganizationService.getOwnerCompanyId();
            if (targetIds.contains(ownerCompanyId)) return null;

            String cypher = """
                MATCH (:Company {companyId: $ownerCompanyId})-[r:PARTNER_WITH|COMPETITOR_OF|POTENTIAL_PARTNER_OF|SUPPLIER_OF|CUSTOMER_OF]-(c:Company)
                WHERE c.companyId IN $targetIds
                RETURN type(r) AS relType
                ORDER BY coalesce(r.confirmedAt, datetime('1970-01-01T00:00:00Z')) DESC
                LIMIT 1
                """;

            java.util.List<String> types = new java.util.ArrayList<>(neo4jClient.query(cypher)
                    .bind(ownerCompanyId).to("ownerCompanyId")
                    .bind(targetIds).to("targetIds")
                    .fetchAs(String.class)
                    .mappedBy((typeSystem, record) -> record.get("relType").asString())
                    .all());

            if (!types.isEmpty()) {
                String relString = types.get(0);
                try {
                    return com.apms.common.enums.RelationshipType.valueOf(relString);
                } catch (IllegalArgumentException e) {
                    log.warn("Unknown relationship type in Neo4j: {}", relString);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to resolve Neo4j relationship for company {}: {}", targetCompanyProfileId, e.getMessage());
        }
        return null;
    }

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

    private void validateKeyResults(List<com.apms.domain.project.dto.CreateProjectKeyResultRequest> keyResults,
                                    ProjectType projectType,
                                    String targetCompanyProfileId,
                                    com.apms.common.enums.RelationshipType targetRelationshipType) {
        if (keyResults == null || keyResults.isEmpty()) {
            return;
        }
        int totalWeight = keyResults.stream().mapToInt(com.apms.domain.project.dto.CreateProjectKeyResultRequest::getWeight).sum();
        if (totalWeight != 100) {
            throw new com.apms.common.exception.BusinessValidationException("KR_WEIGHT_TOTAL_MUST_EQUAL_100", "Total weight of Key Results must be exactly 100.");
        }
        if (keyResults.stream().anyMatch(kr -> kr.getWeight() <= 0)) {
            throw new com.apms.common.exception.BusinessValidationException("KR_WEIGHT_MUST_BE_POSITIVE", "Key Result weight must be greater than 0.");
        }
        long uniqueTypesCount = keyResults.stream().map(com.apms.domain.project.dto.CreateProjectKeyResultRequest::getType).distinct().count();
        if (uniqueTypesCount != keyResults.size()) {
            throw new com.apms.common.exception.BusinessValidationException("KR_DUPLICATE_TYPE", "Duplicate Key Result type found.");
        }

        if (projectType == ProjectType.RESEARCH_NEW_COMPANY) {
            boolean hasBasic = keyResults.stream().anyMatch(kr -> kr.getType() == com.apms.common.enums.ProjectKeyResultType.BASIC_COMPANY_INFORMATION);
            if (!hasBasic) {
                throw new com.apms.common.exception.BusinessValidationException("BASIC_COMPANY_INFORMATION_REQUIRED",
                        "Basic Company Information is required for New Company Research projects.");
            }
        } else if (projectType == ProjectType.UPDATE_EXISTING_COMPANY) {
            if (keyResults.stream().anyMatch(kr ->
                    kr.getType() != com.apms.common.enums.ProjectKeyResultType.FINANCIAL_INFORMATION &&
                    kr.getType() != com.apms.common.enums.ProjectKeyResultType.CONTRACT_INFORMATION)) {
                throw new com.apms.common.exception.BusinessValidationException("INVALID_DELIVERABLE_FOR_EXISTING_COMPANY",
                        "Existing company update projects only support FINANCIAL_INFORMATION and CONTRACT_INFORMATION.");
            }
            if (org.springframework.util.StringUtils.hasText(targetCompanyProfileId)) {
                com.apms.common.enums.RelationshipType canonicalRel = resolveCanonicalRelationship(targetCompanyProfileId);
                if (canonicalRel != null && targetRelationshipType != null && canonicalRel != targetRelationshipType) {
                    if (targetRelationshipType == com.apms.common.enums.RelationshipType.PARTNER_WITH ||
                        targetRelationshipType == com.apms.common.enums.RelationshipType.CUSTOMER_OF ||
                        targetRelationshipType == com.apms.common.enums.RelationshipType.SUPPLIER_OF) {
                        boolean hasContract = keyResults.stream().anyMatch(kr -> kr.getType() == com.apms.common.enums.ProjectKeyResultType.CONTRACT_INFORMATION);
                        if (!hasContract) {
                            throw new com.apms.common.exception.BusinessValidationException("CONTRACT_INFORMATION_REQUIRED",
                                    "Contract Information is required for relationship change from " + canonicalRel + " to " + targetRelationshipType);
                        }
                    }
                }
            }
        }

        if (keyResults.stream().anyMatch(kr -> kr.getType() == com.apms.common.enums.ProjectKeyResultType.CONTRACT_INFORMATION)) {
            if (targetRelationshipType != com.apms.common.enums.RelationshipType.PARTNER_WITH &&
                targetRelationshipType != com.apms.common.enums.RelationshipType.CUSTOMER_OF &&
                targetRelationshipType != com.apms.common.enums.RelationshipType.SUPPLIER_OF) {
                throw new com.apms.common.exception.BusinessValidationException("KR_CONTRACT_INFORMATION_UNSUPPORTED_RELATIONSHIP",
                        "CONTRACT_INFORMATION key result is only supported for PARTNER, CUSTOMER, or SUPPLIER relationships.");
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

        final String profileLookupId = targetProfileId;
        String targetTaxCode = project.getTargetCompanyTaxCode();
        if (!org.springframework.util.StringUtils.hasText(targetTaxCode) && org.springframework.util.StringUtils.hasText(profileLookupId)) {
            java.util.Optional<com.apms.domain.profile.CompanyProfile> profileOpt = companyProfileRepository.findByCompanyId(profileLookupId)
                    .or(() -> companyProfileRepository.findById(profileLookupId));
            if (profileOpt.isPresent() && profileOpt.get().getIdentity() != null && org.springframework.util.StringUtils.hasText(profileOpt.get().getIdentity().getTaxCode())) {
                targetTaxCode = profileOpt.get().getIdentity().getTaxCode();
            }
        }

        com.apms.common.enums.RelationshipType currentRel = null;
        if (project.getProjectType() == ProjectType.UPDATE_EXISTING_COMPANY) {
            if (project.getOriginalRelationshipType() != null) {
                currentRel = project.getOriginalRelationshipType();
            } else if (project.getStatus() == ProjectStatus.COMPLETED && companyProfileVersionRepository != null && org.springframework.util.StringUtils.hasText(profileLookupId)) {
                try {
                    List<com.apms.domain.profile.CompanyProfileVersion> versions = companyProfileVersionRepository
                            .findByCompanyProfileIdOrCompanyIdOrderByCreatedAtDesc(profileLookupId, profileLookupId);
                    if (versions != null) {
                        for (com.apms.domain.profile.CompanyProfileVersion v : versions) {
                            if (project.getId().equals(v.getCreatedFromProjectId()) && v.getBeforeValues() != null) {
                                Object beforeRel = v.getBeforeValues().get("relationship");
                                if (beforeRel != null && org.springframework.util.StringUtils.hasText(beforeRel.toString())) {
                                    try {
                                        currentRel = com.apms.common.enums.RelationshipType.valueOf(beforeRel.toString());
                                        break;
                                    } catch (Exception ignored) {}
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
            if (currentRel == null && org.springframework.util.StringUtils.hasText(profileLookupId)) {
                currentRel = resolveCanonicalRelationship(profileLookupId);
                // Backfill historical originalRelationshipType on project entity if missing (e.g. legacy test13 created before fix)
                if (currentRel != null && project.getOriginalRelationshipType() == null) {
                    project.setOriginalRelationshipType(currentRel);
                    try {
                        projectRepository.save(project);
                    } catch (Exception ignored) {}
                }
            }
        }

        return ProjectResponse.builder()
                .id(project.getId())
                .projectName(project.getProjectName())
                .projectType(project.getProjectType())
                .targetCompanyProfileId(targetProfileId)
                .targetCompanyName(project.getTargetCompanyName())
                .targetCompanyTaxCode(targetTaxCode)
                .targetRelationshipType(project.getTargetRelationshipType())
                .currentRelationshipType(currentRel)
                .originalRelationshipType(project.getOriginalRelationshipType())
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

    public com.apms.domain.project.dto.OpenProjectCheckResponse checkOpenProjectForCompany(
            String companyProfileId,
            String taxCode,
            Long excludeProjectId) {
        java.util.Set<String> profileIds = new java.util.HashSet<>();
        String normalizedTaxCode = org.springframework.util.StringUtils.hasText(taxCode)
                ? taxCode.replaceAll("[\\s\\-]", "").trim()
                : null;

        if (org.springframework.util.StringUtils.hasText(companyProfileId)) {
            profileIds.add(companyProfileId);
            Optional<com.apms.domain.profile.CompanyProfile> profileOpt = companyProfileRepository.findByCompanyId(companyProfileId)
                    .or(() -> companyProfileRepository.findById(companyProfileId));
            if (profileOpt.isPresent()) {
                com.apms.domain.profile.CompanyProfile profile = profileOpt.get();
                if (org.springframework.util.StringUtils.hasText(profile.getId())) {
                    profileIds.add(profile.getId());
                }
                if (org.springframework.util.StringUtils.hasText(profile.getCompanyId())) {
                    profileIds.add(profile.getCompanyId());
                }
                if (normalizedTaxCode == null && profile.getIdentity() != null && org.springframework.util.StringUtils.hasText(profile.getIdentity().getTaxCode())) {
                    normalizedTaxCode = profile.getIdentity().getTaxCode().replaceAll("[\\s\\-]", "").trim();
                }
            }
        } else if (normalizedTaxCode != null) {
            Optional<com.apms.domain.profile.CompanyProfile> profileOpt = companyProfileRepository.findByIdentityTaxCode(normalizedTaxCode);
            if (profileOpt.isPresent()) {
                com.apms.domain.profile.CompanyProfile profile = profileOpt.get();
                if (org.springframework.util.StringUtils.hasText(profile.getId())) {
                    profileIds.add(profile.getId());
                }
                if (org.springframework.util.StringUtils.hasText(profile.getCompanyId())) {
                    profileIds.add(profile.getCompanyId());
                }
            }
        }

        if (profileIds.isEmpty() && normalizedTaxCode == null) {
            return com.apms.domain.project.dto.OpenProjectCheckResponse.builder().hasOpenProject(false).build();
        }

        List<Project> openProjects = projectRepository.findOpenProjectsForCompany(
                profileIds.isEmpty() ? java.util.Collections.singletonList("__NO_MATCH_ID__") : profileIds,
                normalizedTaxCode,
                excludeProjectId
        );

        if (!openProjects.isEmpty()) {
            Project openProj = openProjects.get(0);
            return com.apms.domain.project.dto.OpenProjectCheckResponse.builder()
                    .hasOpenProject(true)
                    .projectId(openProj.getId())
                    .projectName(openProj.getProjectName())
                    .status(openProj.getStatus())
                    .companyName(openProj.getTargetCompanyName())
                    .build();
        }

        return com.apms.domain.project.dto.OpenProjectCheckResponse.builder().hasOpenProject(false).build();
    }

    public com.apms.domain.project.dto.DuplicateTaxCodeCheckResponse checkDuplicateTaxCode(String taxCode) {
        return checkDuplicateTaxCode(taxCode, null);
    }

    public com.apms.domain.project.dto.DuplicateTaxCodeCheckResponse checkDuplicateTaxCode(String taxCode, UserDetailsImpl currentUser) {
        if (!org.springframework.util.StringUtils.hasText(taxCode)) {
            return com.apms.domain.project.dto.DuplicateTaxCodeCheckResponse.builder()
                    .exists(false)
                    .build();
        }
        String normalizedTaxCode = taxCode.replaceAll("[\\s\\-]", "").trim();

        // 1. Check if an official CompanyProfile exists
        Optional<com.apms.domain.profile.CompanyProfile> profileOpt = companyProfileRepository.findByIdentityTaxCode(normalizedTaxCode);
        com.apms.domain.profile.CompanyProfile profile = profileOpt.filter(p -> !Boolean.TRUE.equals(p.getIsDeleted())).orElse(null);
        boolean isOfficial = profile != null && companyProfileOfficialEvaluator.isOfficial(profile);

        // 2. Check for open research projects for this tax code / profile
        List<Project> openProjects = projectRepository.findOpenProjectsByTargetCompanyTaxCode(normalizedTaxCode);
        if (openProjects.isEmpty() && profile != null) {
            com.apms.domain.project.dto.OpenProjectCheckResponse openCheck = checkOpenProjectForCompany(
                    profile.getCompanyId() != null ? profile.getCompanyId() : profile.getId(),
                    normalizedTaxCode,
                    null
            );
            if (openCheck.isHasOpenProject()) {
                Project openProj = projectRepository.findById(openCheck.getProjectId()).orElse(null);
                if (openProj != null) {
                    openProjects = java.util.Collections.singletonList(openProj);
                }
            }
        }

        // CASE C (and Case 4, Case 5): Official company exists
        if (isOfficial) {
            boolean hasOpen = !openProjects.isEmpty();
            Project openProj = hasOpen ? openProjects.get(0) : null;
            boolean canCurrentManagerManage = false;
            if (currentUser != null) {
                boolean isAdmin = currentUser.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SYSTEM_ADMIN"));
                boolean isOwner = currentUser.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_BUSINESS_OWNER"));
                if (isAdmin || isOwner) {
                    canCurrentManagerManage = true;
                } else if (profile.getResponsibleManagerId() != null && profile.getResponsibleManagerId().equals(currentUser.getId())) {
                    canCurrentManagerManage = true;
                }
            }

            return com.apms.domain.project.dto.DuplicateTaxCodeCheckResponse.builder()
                    .exists(true)
                    .matchType("COMPANY_PROFILE")
                    .existingOfficialCompany(true)
                    .canCurrentManagerManage(canCurrentManagerManage)
                    .openResearchProject(false)
                    .companyProfileId(profile.getCompanyId())
                    .companyName(profile.getIdentity() != null ? profile.getIdentity().getLegalName() : null)
                    .taxCode(profile.getIdentity() != null ? profile.getIdentity().getTaxCode() : normalizedTaxCode)
                    .hasOpenProject(hasOpen)
                    .openProjectId(openProj != null ? openProj.getId() : null)
                    .openProjectName(openProj != null ? openProj.getProjectName() : null)
                    .openProjectStatus(openProj != null ? openProj.getStatus() : null)
                    .build();
        }

        // CASE A: An open research project exists for this tax code (no official company yet)
        if (!openProjects.isEmpty()) {
            Project openProj = openProjects.get(0);
            return com.apms.domain.project.dto.DuplicateTaxCodeCheckResponse.builder()
                    .exists(true)
                    .matchType("OPEN_RESEARCH_PROJECT")
                    .existingOfficialCompany(false)
                    .openResearchProject(true)
                    .projectId(openProj.getId())
                    .companyName(openProj.getTargetCompanyName())
                    .taxCode(openProj.getTargetCompanyTaxCode())
                    .hasOpenProject(true)
                    .openProjectId(openProj.getId())
                    .openProjectName(openProj.getProjectName())
                    .openProjectStatus(openProj.getStatus())
                    .build();
        }

        // CASE B: Research was CLOSED without completing, or no record exists.
        // Tax code is available for a new New Company Research project!
        return com.apms.domain.project.dto.DuplicateTaxCodeCheckResponse.builder()
                .exists(false)
                .existingOfficialCompany(false)
                .openResearchProject(false)
                .hasOpenProject(false)
                .build();
    }

    public boolean isOfficialCompanyProfile(com.apms.domain.profile.CompanyProfile profile) {
        return companyProfileOfficialEvaluator.isOfficial(profile);
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

    private void applyTargetRelationshipOnCompletion(Project project, Long actorId) {
        if (project == null || project.getProjectType() != ProjectType.UPDATE_EXISTING_COMPANY) {
            return;
        }
        com.apms.common.enums.RelationshipType targetRel = project.getTargetRelationshipType();
        if (targetRel == null) {
            return;
        }

        String profileLookupId = project.getTargetCompanyProfileId();
        if (!org.springframework.util.StringUtils.hasText(profileLookupId)) {
            return;
        }

        com.apms.domain.profile.CompanyProfile profile = companyProfileRepository.findByCompanyId(profileLookupId)
                .or(() -> companyProfileRepository.findById(profileLookupId))
                .orElse(null);

        if (profile == null) {
            log.warn("Target CompanyProfile not found for project {} with lookup ID {}", project.getId(), profileLookupId);
            return;
        }

        String targetCompanyId = profile.getCompanyId();
        if (!org.springframework.util.StringUtils.hasText(targetCompanyId)) {
            targetCompanyId = profile.getId();
        }

        com.apms.common.enums.RelationshipType canonicalRel = resolveCanonicalRelationship(targetCompanyId);
        if (canonicalRel == null) {
            canonicalRel = resolveCanonicalRelationship(profile.getId());
        }

        // Store originalRelationshipType historically if not already set on the project
        if (project.getOriginalRelationshipType() == null && canonicalRel != null) {
            project.setOriginalRelationshipType(canonicalRel);
            projectRepository.save(project);
        }

        // Idempotency: if canonical relationship already matches target, do nothing
        if (canonicalRel == targetRel) {
            log.info("Project {} relationship apply skipped: canonical relationship is already {}", project.getId(), targetRel);
            return;
        }

        String actorName = "SYSTEM";
        if (actorId != null) {
            actorName = userProfileRepository.findByAccountId(actorId)
                    .map(u -> (u.getFirstName() + " " + u.getLastName()).trim())
                    .filter(name -> !name.isBlank())
                    .orElse("User #" + actorId);
        }

        // 1. Update Neo4j graph relationship
        if (graphService != null) {
            try {
                String ownerCompanyId = ownerOrganizationService.getOwnerCompanyId();
                graphService.replaceRelationship(ownerCompanyId, targetCompanyId, targetRel.name(), actorName);
                log.info("Project {} replaced Neo4j relationship between {} and {} with {}",
                        project.getId(), ownerCompanyId, targetCompanyId, targetRel.name());
            } catch (Exception e) {
                log.error("Failed to update Neo4j relationship for project {}: {}", project.getId(), e.getMessage(), e);
            }
        }

        // 2. Link project to profile sourceRefs and update profile version
        try {
            if (profile.getSourceRefs() == null) {
                profile.setSourceRefs(new com.apms.domain.profile.CompanyProfile.SourceRefs());
            }
            if (profile.getSourceRefs().getProjectIds() == null) {
                profile.getSourceRefs().setProjectIds(new java.util.HashSet<>());
            }
            String projectIdStr = String.valueOf(project.getId());
            profile.getSourceRefs().getProjectIds().add(projectIdStr);

            profile.incrementMajorVersion();
            if (profile.getMetadata() == null) {
                profile.setMetadata(new com.apms.domain.profile.CompanyProfile.Metadata());
            }
            profile.getMetadata().setLastModifiedBy(actorId != null ? String.valueOf(actorId) : "SYSTEM");
            profile.getMetadata().setUpdatedAt(LocalDateTime.now());
            profile = companyProfileRepository.save(profile);

            // 3. Create version snapshot
            if (companyProfileVersionService != null) {
                companyProfileVersionService.createAndSaveVersion(
                        profile,
                        com.apms.domain.profile.enums.CompanyProfileChangeSource.PROJECT_PROFILE_UPDATE,
                        java.util.List.of("relationship"),
                        java.util.Map.of("relationship", canonicalRel != null ? canonicalRel.name() : ""),
                        java.util.Map.of("relationship", targetRel.name()),
                        "Relationship updated from " + (canonicalRel != null ? canonicalRel : "None") + " to " + targetRel + " upon project completion",
                        "Relationship updated to " + targetRel + " via project completion",
                        null,
                        project.getId(),
                        null,
                        null,
                        actorId
                );
            }

            // 4. Log audit action
            auditLogService.log(
                    actorId,
                    AuditAction.CREATE_RELATIONSHIP,
                    "CompanyProfile",
                    targetCompanyId,
                    String.format("Relationship updated from %s to %s on project completion (Project ID: %d)",
                            canonicalRel != null ? canonicalRel : "None", targetRel, project.getId())
            );
        } catch (Exception e) {
            log.error("Failed to update CompanyProfile version/snapshot for project {}: {}", project.getId(), e.getMessage(), e);
        }
    }
}
