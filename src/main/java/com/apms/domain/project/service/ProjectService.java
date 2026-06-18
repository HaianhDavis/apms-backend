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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final AccountRepository accountRepository;

    // ─────────────────────────────────────────────
    // CREATE
    // ─────────────────────────────────────────────

    @Transactional
    public ProjectResponse createProject(CreateProjectRequest request, Long creatorAccountId) {
        validateProjectTypeInvariants(request.getProjectType(),
                request.getTargetCompanyProfileId(),
                request.getTargetCompanyName());

        Project project = Project.builder()
                .projectName(request.getProjectName())
                .projectType(request.getProjectType())
                .targetCompanyProfileId(request.getTargetCompanyProfileId())
                .targetCompanyName(request.getTargetCompanyName())
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
    public Page<ProjectResponse> getAllProjects(ProjectStatus status, ProjectType type, Pageable pageable) {
        Page<Project> page;

        if (status != null && type != null) {
            page = projectRepository.findByStatusAndProjectType(status, type, pageable);
        } else if (status != null) {
            page = projectRepository.findByStatus(status, pageable);
        } else if (type != null) {
            page = projectRepository.findByProjectType(type, pageable);
        } else {
            page = projectRepository.findAll(pageable);
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
        if (request.getStatus() != null) {
            project.setStatus(request.getStatus());
        }

        project = projectRepository.save(project);
        List<ProjectMember> members = projectMemberRepository.findByProject_Id(id);
        return toResponse(project, members);
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
                                               String targetCompanyName) {
        if (!StringUtils.hasText(targetCompanyName)) {
            throw new BusinessValidationException("targetCompanyName is required for all project types.");
        }

        switch (type) {
            case UPDATE_EXISTING_COMPANY -> {
                if (!StringUtils.hasText(targetCompanyProfileId)) {
                    throw new BusinessValidationException(
                            "targetCompanyProfileId is required when projectType is UPDATE_EXISTING_COMPANY.");
                }
            }
            case RESEARCH_NEW_COMPANY, RESEARCH_MULTIPLE_COMPANIES -> {
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
