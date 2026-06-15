package com.apms.domain.project.controller;

import com.apms.common.enums.ProjectStatus;
import com.apms.common.enums.ProjectType;
import com.apms.common.response.ApiResponse;
import com.apms.common.response.PageResponse;
import com.apms.domain.project.dto.*;
import com.apms.domain.project.service.ProjectService;
import com.apms.security.UserDetailsImpl;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    // ─────────────────────────────────────────────
    // POST /api/v1/projects
    // Role: BUSINESS_DEVELOPMENT_MANAGER
    // ─────────────────────────────────────────────
    @PostMapping
    @PreAuthorize("hasRole('BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<ApiResponse<ProjectResponse>> createProject(
            @Valid @RequestBody CreateProjectRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        ProjectResponse response = projectService.createProject(request, currentUser.getId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "Project created successfully"));
    }

    // ─────────────────────────────────────────────
    // GET /api/v1/projects?status=&type=&page=&size=
    // Role: BUSINESS_OWNER, BUSINESS_DEVELOPMENT_MANAGER
    // ─────────────────────────────────────────────
    @GetMapping
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<ApiResponse<PageResponse<ProjectResponse>>> getAllProjects(
            @RequestParam(required = false) ProjectStatus status,
            @RequestParam(required = false) ProjectType type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        PageResponse<ProjectResponse> response = PageResponse.of(
                projectService.getAllProjects(status, type, pageable));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ─────────────────────────────────────────────
    // GET /api/v1/projects/{id}
    // Role: All authenticated
    // ─────────────────────────────────────────────
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'RESEARCH_STAFF') and @projectSecurity.isProjectReadable(#id)")
    public ResponseEntity<ApiResponse<ProjectResponse>> getProjectById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(projectService.getProjectById(id)));
    }

    // ─────────────────────────────────────────────
    // PUT /api/v1/projects/{id}
    // Role: BUSINESS_DEVELOPMENT_MANAGER
    // ─────────────────────────────────────────────
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<ApiResponse<ProjectResponse>> updateProject(
            @PathVariable Long id,
            @RequestBody UpdateProjectRequest request) {

        return ResponseEntity.ok(ApiResponse.success(
                projectService.updateProject(id, request), "Project updated successfully"));
    }

    // ─────────────────────────────────────────────
    // GET /api/v1/projects/{id}/members
    // Role: BUSINESS_DEVELOPMENT_MANAGER, RESEARCH_STAFF
    // ─────────────────────────────────────────────
    @GetMapping("/{id}/members")
    @PreAuthorize("hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'RESEARCH_STAFF') and @projectSecurity.isMember(#id)")
    public ResponseEntity<ApiResponse<List<ProjectMemberResponse>>> getProjectMembers(
            @PathVariable Long id) {

        return ResponseEntity.ok(ApiResponse.success(projectService.getProjectMembers(id)));
    }

    // ─────────────────────────────────────────────
    // POST /api/v1/projects/{id}/members
    // Role: BUSINESS_DEVELOPMENT_MANAGER
    // ─────────────────────────────────────────────
    @PostMapping("/{id}/members")
    @PreAuthorize("hasRole('BUSINESS_DEVELOPMENT_MANAGER') and @projectSecurity.isMember(#id)")
    public ResponseEntity<ApiResponse<ProjectMemberResponse>> addMember(
            @PathVariable Long id,
            @Valid @RequestBody AddMemberRequest request) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        projectService.addMember(id, request), "Member added successfully"));
    }

    // ─────────────────────────────────────────────
    // DELETE /api/v1/projects/{id}/members/{userId}
    // Role: BUSINESS_DEVELOPMENT_MANAGER
    // ─────────────────────────────────────────────
    @DeleteMapping("/{id}/members/{userId}")
    @PreAuthorize("hasRole('BUSINESS_DEVELOPMENT_MANAGER') and @projectSecurity.isMember(#id)")
    public ResponseEntity<ApiResponse<Void>> removeMember(
            @PathVariable Long id,
            @PathVariable Long userId) {

        projectService.removeMember(id, userId);
        return ResponseEntity.ok(ApiResponse.success(null, "Member removed successfully"));
    }
}
