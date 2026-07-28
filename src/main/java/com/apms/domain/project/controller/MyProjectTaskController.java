package com.apms.domain.project.controller;

import com.apms.common.enums.TaskStatus;
import com.apms.common.response.ApiResponse;
import com.apms.common.response.PageResponse;
import com.apms.domain.project.dto.ProjectTaskResponse;
import com.apms.domain.project.service.ProjectTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Queue endpoint intentionally derives the assignee from the authenticated account. */
@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class MyProjectTaskController {

    private final ProjectTaskService projectTaskService;

    @GetMapping("/my")
    @PreAuthorize("hasRole('BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<ApiResponse<PageResponse<ProjectTaskResponse>>> getMyTasks(
            @RequestParam(required = false) TaskStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        PageRequest pageable = PageRequest.of(page, Math.min(size, 100), Sort.by(Sort.Direction.DESC, "updatedAt"));
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(projectTaskService.getMyTasks(status, pageable))));
    }
}
