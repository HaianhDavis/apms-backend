package com.apms.domain.project.service;

import com.apms.common.enums.MemberRole;
import com.apms.common.enums.ProjectStatus;
import com.apms.common.enums.ProjectType;
import com.apms.common.exception.BusinessValidationException;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectMember;
import com.apms.domain.project.dto.*;
import com.apms.domain.project.repository.sql.ProjectMemberRepository;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.user.repository.sql.AccountRepository;
import com.apms.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.common.enums.AuditAction;
import com.apms.common.enums.TaskStatus;
import com.apms.domain.project.dto.UpdateProjectStatusRequest;
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
    private final ProjectMemberRepository projectMemberRepository;
    private final AccountRepository accountRepository;
    private final Neo4jClient neo4jClient;
    private final ProjectTaskRepository projectTaskRepository;
    private final AuditLogService auditLogService;
    private final com.apms.domain.profile.service.OwnerOrganizationService ownerOrganizationService;

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

        validateProjectTypeInvariants(request.getProjectType(),
                request.getTargetCompanyProfileId(),
                request.getTargetCompanyName(),
                resolvedRelationshipType);

        Project project = Project.builder()
                .projectName(request.getProjectName())
                .projectType(request.getProjectType())
                .targetCompanyProfileId(request.getTargetCompanyProfileId())
                .targetCompanyName(request.getTargetCompanyName())
                .targetRelationshipType(resolvedRelationshipType)
                .description(request.getDescription())
                .status(ProjectStatus.DRAFT)
                .createdByAccount(accountRepository.getReferenceById(creatorAccountId))
                .build();

        project = projectRepository.save(project);

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
    public Page<ProjectResponse> getAllProjects(ProjectStatus status, ProjectType type, Pageable pageable, UserDetailsImpl currentUser) {
        Page<Project> page;
        boolean isStaff = currentUser != null && currentUser.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_BUSINESS_DEVELOPMENT_STAFF"));

        if (isStaff) {
            page = projectRepository.findByMemberAccountId(currentUser.getId(), pageable);
        } else {
            if (status != null && type != null) {
                page = projectRepository.findByStatusAndProjectType(status, type, pageable);
            } else if (status != null) {
                page = projectRepository.findByStatus(status, pageable);
            } else if (type != null) {
                page = projectRepository.findByProjectType(type, pageable);
            } else {
                page = projectRepository.findAll(pageable);
            }
        }

        return page.map(p -> {
            List<ProjectMember> members = projectMemberRepository.findByProject_Id(p.getId());
            return toResponse(p, members);
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
        if (request.getTargetRelationshipType() != null) {
            project.setTargetRelationshipType(request.getTargetRelationshipType());
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
                    id, Arrays.asList(TaskStatus.TODO, TaskStatus.IN_PROGRESS, TaskStatus.IN_REVIEW, TaskStatus.BLOCKED));
            if (unfinishedCount > 0) {
                throw new BusinessValidationException("Project cannot be completed while there are unfinished tasks (" + unfinishedCount + ").");
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
    public ProjectMemberResponse addMember(Long projectId, AddMemberRequest request) {
        findProjectOrThrow(projectId);

        if (!accountRepository.existsById(request.getAccountId())) {
            throw new ResourceNotFoundException("Account not found with id: " + request.getAccountId());
        }
        if (projectMemberRepository.existsByProject_IdAndAccount_Id(projectId, request.getAccountId())) {
            throw new BusinessValidationException("Account " + request.getAccountId() + " is already a member of project " + projectId);
        }

        Project project = findProjectOrThrow(projectId);
        ProjectMember member = ProjectMember.builder()
                .project(project)
                .account(accountRepository.getReferenceById(request.getAccountId()))
                .memberRole(request.getMemberRole())
                .build();

        member = projectMemberRepository.save(member);
        log.info("Member added: projectId={}, accountId={}, role={}", projectId, request.getAccountId(), request.getMemberRole());
        return toMemberResponse(member);
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

        projectMemberRepository.deleteByProject_IdAndAccount_Id(projectId, accountId);
        log.info("Member removed: projectId={}, accountId={}", projectId, accountId);
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

    private Project findProjectOrThrow(Long id) {
        return projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found with id: " + id));
    }

    private ProjectResponse toResponse(Project project, List<ProjectMember> members) {
        return ProjectResponse.builder()
                .id(project.getId())
                .projectName(project.getProjectName())
                .projectType(project.getProjectType())
                .targetCompanyProfileId(project.getTargetCompanyProfileId())
                .targetCompanyName(project.getTargetCompanyName())
                .targetRelationshipType(project.getTargetRelationshipType())
                .description(project.getDescription())
                .status(project.getStatus())
                .createdBy(project.getCreatedById())
                .createdAt(project.getCreatedAt())
                .updatedAt(project.getUpdatedAt())
                .members(members.stream().map(this::toMemberResponse).collect(Collectors.toList()))
                .build();
    }

    private ProjectMemberResponse toMemberResponse(ProjectMember m) {
        return ProjectMemberResponse.builder()
                .id(m.getId())
                .accountId(m.getAccountId())
                .memberRole(m.getMemberRole())
                .joinedAt(m.getJoinedAt())
                .build();
    }
}
