package com.apms.domain.project.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.SystemRole;
import com.apms.common.enums.TaskStatus;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.dto.CreateProjectTaskRequest;
import com.apms.domain.project.dto.ProjectTaskResponse;
import com.apms.domain.project.dto.UpdateProjectTaskRequest;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectTaskService {

    private final ProjectTaskRepository projectTaskRepository;
    private final ProjectRepository projectRepository;
    private final AccountRepository accountRepository;
    private final AuditLogService auditLogService;

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
                .build();

        task = projectTaskRepository.save(task);

        auditLogService.log(currentUser.getId(), AuditAction.PROJECT_TASK_CREATED, "ProjectTask", String.valueOf(task.getId()), "Task created");
        if (assignedTo != null) {
            auditLogService.log(currentUser.getId(), AuditAction.PROJECT_TASK_ASSIGNED, "ProjectTask", String.valueOf(task.getId()), "Task assigned to user: " + assignedTo.getId());
        }

        return toResponse(task);
    }

    @Transactional(readOnly = true)
    public Page<ProjectTaskResponse> getTasks(Long projectId, TaskStatus status, Long assignedToUserId, Pageable pageable) {
        Specification<ProjectTask> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("project").get("id"), projectId));

            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (assignedToUserId != null) {
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

        if (!isStaff || isAdminOrManager) {
            if (request.getTitle() != null) task.setTitle(request.getTitle());
            if (request.getDescription() != null) task.setDescription(request.getDescription());
            if (request.getPriority() != null) task.setPriority(request.getPriority());
            if (request.getDueDate() != null) task.setDueDate(request.getDueDate());

            if (request.getAssignedToUserId() != null) {
                Long currentAssignedId = task.getAssignedToAccount() != null ? task.getAssignedToAccount().getId() : null;
                if (!request.getAssignedToUserId().equals(currentAssignedId)) {
                    if (!projectRepository.existsByIdAndMembersAccountId(projectId, request.getAssignedToUserId())) {
                        throw new IllegalArgumentException("Assigned user must be a project member");
                    }
                    Account newAssignedTo = accountRepository.findById(request.getAssignedToUserId())
                            .orElseThrow(() -> new ResourceNotFoundException("Assigned account not found"));
                    task.setAssignedToAccount(newAssignedTo);
                    auditLogService.log(currentUser.getId(), AuditAction.PROJECT_TASK_ASSIGNED, "ProjectTask", String.valueOf(task.getId()), "Task reassigned to user: " + newAssignedTo.getId());
                }
            }
        }

        task = projectTaskRepository.save(task);

        auditLogService.log(currentUser.getId(), AuditAction.PROJECT_TASK_UPDATED, "ProjectTask", String.valueOf(task.getId()), "Task details updated");
        if (statusChanged) {
            auditLogService.log(currentUser.getId(), AuditAction.PROJECT_TASK_STATUS_CHANGED, "ProjectTask", String.valueOf(task.getId()), "Task status changed to " + task.getStatus());
        }

        return toResponse(task);
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
