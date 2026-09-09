package com.apms.domain.project.controller;

import com.apms.common.enums.TaskStatus;
import com.apms.common.response.ApiResponse;
import com.apms.common.response.PageResponse;
import com.apms.domain.project.dto.CreateProjectTaskRequest;
import com.apms.domain.project.dto.ProjectTaskActivityResponse;
import com.apms.domain.project.dto.ProjectTaskResponse;
import com.apms.domain.project.dto.ProjectTaskWorkbenchResponse;
import com.apms.domain.project.dto.StaffWorkHistoryItemResponse;
import com.apms.domain.project.dto.TaskHistoryDetailResponse;
import com.apms.domain.project.dto.UpdateProjectTaskRequest;
import com.apms.domain.project.service.ProjectTaskService;
import com.apms.security.UserDetailsImpl;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/tasks")
@RequiredArgsConstructor
public class ProjectTaskController {

    private final ProjectTaskService projectTaskService;

    @PostMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or (hasRole('BUSINESS_DEVELOPMENT_MANAGER') and @projectSecurity.isMemberOrOwner(#projectId))")
    public ResponseEntity<ApiResponse<ProjectTaskResponse>> createTask(
            @PathVariable Long projectId,
            @Valid @RequestBody CreateProjectTaskRequest request) {

        return ResponseEntity.ok(ApiResponse.success(projectTaskService.createTask(projectId, request), "Task created"));
    }

    @GetMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or (hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF') and @projectSecurity.isMemberOrOwner(#projectId))")
    public ResponseEntity<ApiResponse<PageResponse<ProjectTaskResponse>>> getTasks(
            @PathVariable Long projectId,
            @RequestParam(required = false) TaskStatus status,
            @RequestParam(required = false) Long assignedToUserId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        boolean staffOnly = currentUser.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_BUSINESS_DEVELOPMENT_STAFF".equals(authority.getAuthority()));
        Long effectiveAssignedToUserId;
        if (staffOnly) {
            if (status == TaskStatus.AVAILABLE) {
                effectiveAssignedToUserId = null; // Unassigned pool
            } else {
                effectiveAssignedToUserId = currentUser.getId(); // Only their own tasks
            }
        } else {
            effectiveAssignedToUserId = assignedToUserId;
        }
        
        PageResponse<ProjectTaskResponse> response = PageResponse.of(
                projectTaskService.getTasks(projectId, status, effectiveAssignedToUserId, pageable, staffOnly && status == TaskStatus.AVAILABLE));

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PatchMapping("/{taskId}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or (hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF') and @projectSecurity.isMemberOrOwner(#projectId))")
    public ResponseEntity<ApiResponse<ProjectTaskResponse>> updateTask(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @Valid @RequestBody UpdateProjectTaskRequest request) {

        return ResponseEntity.ok(ApiResponse.success(projectTaskService.updateTask(projectId, taskId, request), "Task updated"));
    }

    @DeleteMapping("/{taskId}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or (hasRole('BUSINESS_DEVELOPMENT_MANAGER') and @projectSecurity.isMemberOrOwner(#projectId))")
    public ResponseEntity<ApiResponse<Void>> deleteTask(
            @PathVariable Long projectId,
            @PathVariable Long taskId) {

        projectTaskService.deleteTask(projectId, taskId);
        return ResponseEntity.ok(ApiResponse.success(null, "Task deleted"));
    }

    @GetMapping("/{taskId}/activity")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or (hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF') and @projectSecurity.isMemberOrOwner(#projectId))")
    public ResponseEntity<ApiResponse<java.util.List<ProjectTaskActivityResponse>>> getTaskActivity(
            @PathVariable Long projectId,
            @PathVariable Long taskId) {

        return ResponseEntity.ok(ApiResponse.success(projectTaskService.getTaskActivity(projectId, taskId)));
    }

    @GetMapping("/{taskId}/workbench")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or (hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF') and @projectSecurity.isMemberOrOwner(#projectId))")
    public ResponseEntity<ApiResponse<ProjectTaskWorkbenchResponse>> getTaskWorkbench(
            @PathVariable Long projectId,
            @PathVariable Long taskId) {

        return ResponseEntity.ok(ApiResponse.success(projectTaskService.getTaskWorkbench(projectId, taskId)));
    }

    @PostMapping("/{taskId}/claim")
    @PreAuthorize("hasRole('BUSINESS_DEVELOPMENT_STAFF') and @projectSecurity.isMemberOrOwner(#projectId)")
    public ResponseEntity<ApiResponse<ProjectTaskResponse>> claimTask(
            @PathVariable Long projectId,
            @PathVariable Long taskId) {

        return ResponseEntity.ok(ApiResponse.success(projectTaskService.claimTask(projectId, taskId), "Task claimed successfully"));
    }

    @PostMapping("/{taskId}/release")
    @PreAuthorize("hasRole('BUSINESS_DEVELOPMENT_STAFF') and @projectSecurity.isMemberOrOwner(#projectId)")
    public ResponseEntity<ApiResponse<ProjectTaskResponse>> releaseTask(
            @PathVariable Long projectId,
            @PathVariable Long taskId) {

        return ResponseEntity.ok(ApiResponse.success(projectTaskService.releaseTask(projectId, taskId), "Task released successfully"));
    }

    @GetMapping("/my-history")
    @PreAuthorize("hasRole('BUSINESS_DEVELOPMENT_STAFF') and @projectSecurity.isMember(#projectId)")
    public ResponseEntity<ApiResponse<java.util.List<StaffWorkHistoryItemResponse>>> getMyWorkHistory(
            @PathVariable Long projectId) {

        return ResponseEntity.ok(ApiResponse.success(projectTaskService.getMyWorkHistory(projectId)));
    }

    @GetMapping("/{taskId}/history")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or (hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF') and @projectSecurity.isMemberOrOwner(#projectId))")
    public ResponseEntity<ApiResponse<TaskHistoryDetailResponse>> getTaskHistory(
            @PathVariable Long projectId,
            @PathVariable Long taskId) {

        return ResponseEntity.ok(ApiResponse.success(projectTaskService.getTaskHistory(projectId, taskId)));
    }
}
