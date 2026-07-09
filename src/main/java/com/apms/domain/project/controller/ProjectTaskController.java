package com.apms.domain.project.controller;

import com.apms.common.enums.TaskStatus;
import com.apms.common.response.ApiResponse;
import com.apms.common.response.PageResponse;
import com.apms.domain.project.dto.CreateProjectTaskRequest;
import com.apms.domain.project.dto.ProjectTaskResponse;
import com.apms.domain.project.dto.ProjectTaskWorkbenchResponse;
import com.apms.domain.project.dto.UpdateProjectTaskRequest;
import com.apms.domain.project.service.ProjectTaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or (hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF') and @projectSecurity.isMemberOrOwner(#projectId))")
    public ResponseEntity<ApiResponse<PageResponse<ProjectTaskResponse>>> getTasks(
            @PathVariable Long projectId,
            @RequestParam(required = false) TaskStatus status,
            @RequestParam(required = false) Long assignedToUserId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        PageResponse<ProjectTaskResponse> response = PageResponse.of(
                projectTaskService.getTasks(projectId, status, assignedToUserId, pageable));
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PatchMapping("/{taskId}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or (hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF') and @projectSecurity.isMemberOrOwner(#projectId))")
    public ResponseEntity<ApiResponse<ProjectTaskResponse>> updateTask(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @Valid @RequestBody UpdateProjectTaskRequest request) {

        return ResponseEntity.ok(ApiResponse.success(projectTaskService.updateTask(projectId, taskId, request), "Task updated"));
    }

    @GetMapping("/{taskId}/workbench")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or (hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF') and @projectSecurity.isMemberOrOwner(#projectId))")
    public ResponseEntity<ApiResponse<ProjectTaskWorkbenchResponse>> getTaskWorkbench(
            @PathVariable Long projectId,
            @PathVariable Long taskId) {

        return ResponseEntity.ok(ApiResponse.success(projectTaskService.getTaskWorkbench(projectId, taskId)));
    }
}
