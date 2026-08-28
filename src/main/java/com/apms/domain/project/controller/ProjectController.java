package com.apms.domain.project.controller;

import com.apms.common.enums.ProjectStatus;
import com.apms.common.enums.ProjectType;
import com.apms.common.enums.RelationshipType;
import com.apms.common.response.ApiResponse;
import com.apms.common.response.PageResponse;
import com.apms.domain.project.dto.*;
import com.apms.domain.project.service.ProjectService;
import com.apms.security.UserDetailsImpl;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;
    private final com.apms.domain.profile.service.ProfileService profileService;

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
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<ApiResponse<PageResponse<ProjectResponse>>> getAllProjects(
            @RequestParam(required = false) ProjectStatus status,
            @RequestParam(required = false) ProjectType type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        boolean restrictToMembership = currentUser.getAuthorities().stream()
                .noneMatch(authority -> "ROLE_BUSINESS_OWNER".equals(authority.getAuthority()));
        PageResponse<ProjectResponse> response = PageResponse.of(
                projectService.getAllProjects(status, type, pageable, currentUser.getId(), restrictToMembership));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ─────────────────────────────────────────────
    // GET /api/v1/projects/check-duplicate-company
    // Role: BUSINESS_OWNER, BUSINESS_DEVELOPMENT_MANAGER
    // ─────────────────────────────────────────────
    @GetMapping("/check-duplicate-company")
    @Operation(summary = "Check for duplicate company by name")
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<ApiResponse<DuplicateCompanyCheckResponse>> checkDuplicateCompany(
            @RequestParam("companyName") String companyName,
            @RequestParam(value = "excludeProjectId", required = false) Long excludeProjectId) {

        DuplicateCompanyCheckResponse response = projectService.checkDuplicateCompanyName(companyName, excludeProjectId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // GET /api/v1/projects/check-duplicate-tax-code
    @GetMapping("/check-duplicate-tax-code")
    @Operation(summary = "Check for duplicate company by tax code")
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<ApiResponse<com.apms.domain.project.dto.DuplicateTaxCodeCheckResponse>> checkDuplicateTaxCode(
            @RequestParam("taxCode") String taxCode) {
        com.apms.domain.project.dto.DuplicateTaxCodeCheckResponse response = projectService.checkDuplicateTaxCode(taxCode);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ─────────────────────────────────────────────
    // GET /api/v1/projects/{id}
    // Role: All authenticated
    // ─────────────────────────────────────────────
    @GetMapping("/relationship-types")
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<ApiResponse<List<Map<String, String>>>> getTargetRelationshipTypes() {
        List<Map<String, String>> response = Arrays.stream(RelationshipType.values())
                .map(type -> Map.of(
                        "value", type.name(),
                        "label", relationshipTypeLabel(type)))
                .toList();

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    private String relationshipTypeLabel(RelationshipType type) {
        return switch (type) {
            case PARTNER_WITH -> "Partner";
            case COMPETITOR_OF -> "Competitor";
            case SUPPLIER_OF -> "Supplier";
            case CUSTOMER_OF -> "Customer";
            case POTENTIAL_PARTNER_OF -> "Potential partner";
        };
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF') and @projectSecurity.isProjectReadable(#id)")
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
    // POST /api/v1/projects/{id}/close
    // Role: BUSINESS_DEVELOPMENT_MANAGER
    // ─────────────────────────────────────────────
    @PostMapping("/{id}/close")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or (hasRole('BUSINESS_DEVELOPMENT_MANAGER') and @projectSecurity.isMember(#id))")
    public ResponseEntity<ApiResponse<ProjectResponse>> closeProject(
            @PathVariable Long id,
            @RequestBody CloseProjectRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        
        return ResponseEntity.ok(ApiResponse.success(
                projectService.closeProject(id, request, currentUser.getId()), "Project closed successfully"));
    }

    // ─────────────────────────────────────────────
    // PATCH /api/v1/projects/{id}/status
    // Role: SYSTEM_ADMIN, BUSINESS_DEVELOPMENT_MANAGER
    // ─────────────────────────────────────────────
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or (hasRole('BUSINESS_DEVELOPMENT_MANAGER') and @projectSecurity.isMember(#id))")
    public ResponseEntity<ApiResponse<ProjectResponse>> updateProjectStatus(
            @PathVariable Long id,
            @Valid @RequestBody UpdateProjectStatusRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        return ResponseEntity.ok(ApiResponse.success(
                projectService.updateProjectStatus(id, request, currentUser.getId()), "Project status updated successfully"));
    }

    // ─────────────────────────────────────────────
    // GET /api/v1/projects/{id}/members
    // Role: BUSINESS_DEVELOPMENT_MANAGER, BUSINESS_DEVELOPMENT_STAFF
    // ─────────────────────────────────────────────
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or (hasRole('BUSINESS_DEVELOPMENT_MANAGER') and @projectSecurity.isMember(#id))")
    public ResponseEntity<ApiResponse<Void>> deleteProject(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        projectService.deleteProject(id, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(null, "Project deleted successfully"));
    }

    @GetMapping("/{id}/members")
    @PreAuthorize("hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF') and @projectSecurity.isMember(#id)")
    public ResponseEntity<ApiResponse<List<ProjectMemberResponse>>> getProjectMembers(
            @PathVariable Long id) {

        return ResponseEntity.ok(ApiResponse.success(projectService.getProjectMembers(id)));
    }

    // ─────────────────────────────────────────────
    // POST /api/v1/projects/{id}/members
    // Role: BUSINESS_DEVELOPMENT_MANAGER
    // ─────────────────────────────────────────────
    @PostMapping("/{id}/members")
    @PreAuthorize("hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF') and @projectSecurity.isMember(#id)")
    public ResponseEntity<ApiResponse<ProjectMemberResponse>> addMember(
            @PathVariable Long id,
            @Valid @RequestBody AddMemberRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        projectService.addMember(id, request, currentUser.getId()), "Member added successfully"));
    }

    // ─────────────────────────────────────────────
    // DELETE /api/v1/projects/{id}/members/{userId}
    // Role: BUSINESS_DEVELOPMENT_MANAGER
    @DeleteMapping("/{id}/members/{userId}")
    @PreAuthorize("hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF') and @projectSecurity.isMember(#id)")
    public ResponseEntity<ApiResponse<Void>> removeMember(
            @PathVariable Long id,
            @PathVariable Long userId,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        projectService.removeMember(id, userId, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(null, "Member removed successfully"));
    }

    @PutMapping("/{id}/members/{userId}/role")
    @PreAuthorize("hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF') and @projectSecurity.isMember(#id)")
    public ResponseEntity<ApiResponse<ProjectMemberResponse>> updateMemberRole(
            @PathVariable Long id,
            @PathVariable Long userId,
            @Valid @RequestBody com.apms.domain.project.dto.UpdateMemberRoleRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        return ResponseEntity.ok(ApiResponse.success(
                projectService.updateMemberRole(id, userId, request.getProjectRole(), currentUser.getId()), "Member role updated successfully"));
    }

    @PostMapping("/{id}/members/transfer-leadership")
    @PreAuthorize("hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF') and @projectSecurity.isMember(#id)")
    public ResponseEntity<ApiResponse<Void>> transferLeadership(
            @PathVariable Long id,
            @Valid @RequestBody com.apms.domain.project.dto.TransferLeadershipRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        projectService.transferLeadership(id, request.getNewLeaderAccountId(), request.isLeaveProject(), currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(null, "Leadership transferred successfully"));
    }

    @PostMapping("/{id}/members/leave")
    @PreAuthorize("hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF') and @projectSecurity.isMember(#id)")
    public ResponseEntity<ApiResponse<Void>> leaveProject(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        projectService.leaveProject(id, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(null, "Left project successfully"));
    }

    // ─────────────────────────────────────────────
    // PATCH /api/v1/projects/{projectId}/company-profiles/{companyId}/visibility
    // Role: BUSINESS_DEVELOPMENT_MANAGER (must also be LEADER of project)
    // ─────────────────────────────────────────────
    @PatchMapping("/{projectId}/company-profiles/{companyId}/visibility")
    @Operation(summary = "Update company profile visibility strictly through project governance")
    @PreAuthorize("hasRole('BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<ApiResponse<com.apms.domain.profile.dto.ProfileResponse>> updateVisibility(
            @PathVariable Long projectId,
            @PathVariable String companyId,
            @Valid @RequestBody com.apms.domain.profile.dto.UpdateProfileVisibilityRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        String canonicalCompanyId = projectService.validateAndRepairProjectProfileGovernance(projectId, companyId, currentUser.getId());

        // We delegate to profileService to actually mutate and save the profile
        // using the resolved canonical ID to avoid 'Company profile not found' errors.
        com.apms.domain.profile.dto.ProfileResponse response = profileService.updateVisibility(canonicalCompanyId, request, currentUser.getId());
        
        return ResponseEntity.ok(ApiResponse.success(response, "Visibility updated successfully via project governance"));
    }
}
