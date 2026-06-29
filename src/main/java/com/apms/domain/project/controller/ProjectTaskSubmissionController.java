package com.apms.domain.project.controller;

import com.apms.common.response.PageResponse;
import com.apms.domain.project.dto.CreateProjectTaskSubmissionRequest;
import com.apms.domain.project.dto.ProjectTaskSubmissionResponse;
import com.apms.domain.project.dto.ReviewTaskSubmissionRequest;
import com.apms.domain.project.service.ProjectTaskSubmissionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/tasks/{taskId}/submissions")
@RequiredArgsConstructor
public class ProjectTaskSubmissionController {

    private final ProjectTaskSubmissionService submissionService;

    @PostMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<ProjectTaskSubmissionResponse> submitTask(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @Valid @RequestBody CreateProjectTaskSubmissionRequest request) {
        return ResponseEntity.ok(submissionService.submitTask(projectId, taskId, request));
    }

    @GetMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<PageResponse<ProjectTaskSubmissionResponse>> getSubmissions(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<ProjectTaskSubmissionResponse> pageResult = submissionService.getSubmissions(projectId, taskId, pageable);
        
        PageResponse<ProjectTaskSubmissionResponse> response = new PageResponse<>(
                pageResult.getContent(),
                pageResult.getNumber(),
                pageResult.getSize(),
                pageResult.getTotalElements(),
                pageResult.getTotalPages(),
                pageResult.isLast()
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{submissionId}/review")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<ProjectTaskSubmissionResponse> reviewSubmission(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @PathVariable Long submissionId,
            @Valid @RequestBody ReviewTaskSubmissionRequest request) {
        return ResponseEntity.ok(submissionService.reviewSubmission(projectId, taskId, submissionId, request));
    }
}
