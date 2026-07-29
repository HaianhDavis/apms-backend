package com.apms.domain.candidate.controller;

import com.apms.common.response.ApiResponse;
import com.apms.common.response.PageResponse;
import com.apms.domain.candidate.dto.ApproveCandidateRequest;
import com.apms.domain.candidate.dto.CandidateResponse;
import com.apms.domain.candidate.dto.RejectCandidateRequest;
import com.apms.domain.candidate.dto.UpdateCandidateRequest;
import com.apms.domain.candidate.service.CandidateService;
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

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CandidateController {

    private final CandidateService candidateService;

    // ─────────────────────────────────────────────
    // POST /api/v1/import-jobs/{importJobId}/candidates/from-ai (Legacy)
    // ─────────────────────────────────────────────
    @Deprecated
    @PostMapping("/import-jobs/{importJobId}/candidates/from-ai")
    @PreAuthorize("hasRole('BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<ApiResponse<CandidateResponse>> createFromAi(
            @PathVariable Long importJobId,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        CandidateResponse response = candidateService.createFromAi(importJobId, currentUser.getId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "Candidate created from AI extraction (legacy)"));
    }

    // ─────────────────────────────────────────────
    // POST /api/v1/ai-extractions/{extractionId}/candidate
    // ─────────────────────────────────────────────
    @PostMapping("/ai-extractions/{extractionId}/candidate")
    @PreAuthorize("hasRole('BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<ApiResponse<CandidateResponse>> createFromExtractionId(
            @PathVariable String extractionId,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        CandidateResponse response = candidateService.createFromExtractionId(extractionId, currentUser.getId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "Candidate created from reviewed AI extraction"));
    }

    // ─────────────────────────────────────────────
    // GET /api/v1/projects/{projectId}/candidates
    // Role: BUSINESS_DEVELOPMENT_STAFF, BUSINESS_DEVELOPMENT_MANAGER, BUSINESS_OWNER
    // ─────────────────────────────────────────────
    @GetMapping("/projects/{projectId}/candidates")
    @PreAuthorize("hasAnyRole('BUSINESS_DEVELOPMENT_STAFF', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_OWNER') and @projectSecurity.isMemberOrOwner(#projectId == null ? -1 : Long.parseLong(#projectId))")
    public ResponseEntity<ApiResponse<PageResponse<CandidateResponse>>> getProjectCandidates(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "candidateOrder"));
        PageResponse<CandidateResponse> response = PageResponse.of(
                candidateService.getProjectCandidates(projectId, pageable));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ─────────────────────────────────────────────
    // GET /api/v1/candidates/{candidateId}
    // Role: BUSINESS_DEVELOPMENT_STAFF, BUSINESS_DEVELOPMENT_MANAGER, BUSINESS_OWNER
    // ─────────────────────────────────────────────
    @GetMapping("/candidates/{candidateId}")
    @PreAuthorize("hasAnyRole('BUSINESS_DEVELOPMENT_STAFF', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_OWNER') and @projectSecurity.canAccessCandidate(#candidateId)")
    public ResponseEntity<ApiResponse<CandidateResponse>> getCandidate(
            @PathVariable String candidateId) {

        return ResponseEntity.ok(ApiResponse.success(candidateService.getCandidate(candidateId)));
    }

    // ─────────────────────────────────────────────
    // PATCH /api/v1/candidates/{candidateId}
    // Role: BUSINESS_DEVELOPMENT_STAFF
    // ─────────────────────────────────────────────
    @PatchMapping("/candidates/{candidateId}")
    @PreAuthorize("hasRole('BUSINESS_DEVELOPMENT_STAFF') and @projectSecurity.canModifyCandidate(#candidateId)")
    public ResponseEntity<ApiResponse<CandidateResponse>> updateCandidate(
            @PathVariable String candidateId,
            @RequestBody UpdateCandidateRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        return ResponseEntity.ok(ApiResponse.success(
                candidateService.updateCandidate(candidateId, request, currentUser.getId()), "Candidate updated successfully"));
    }

    // ─────────────────────────────────────────────
    // POST /api/v1/candidates/{candidateId}/submit
    // Role: BUSINESS_DEVELOPMENT_STAFF
    // ─────────────────────────────────────────────
    @PostMapping("/candidates/{candidateId}/submit")
    @PreAuthorize("hasRole('BUSINESS_DEVELOPMENT_STAFF') and @projectSecurity.canModifyCandidate(#candidateId)")
    public ResponseEntity<ApiResponse<CandidateResponse>> submitCandidate(
            @PathVariable String candidateId) {

        return ResponseEntity.ok(ApiResponse.success(
                candidateService.submitCandidate(candidateId), "Candidate submitted for review"));
    }

    // ─────────────────────────────────────────────
    // POST /api/v1/candidates/{candidateId}/correct
    // Role: BUSINESS_DEVELOPMENT_STAFF
    // ─────────────────────────────────────────────
    @PostMapping("/candidates/{candidateId}/correct")
    @PreAuthorize("hasRole('BUSINESS_DEVELOPMENT_STAFF') and @projectSecurity.canModifyCandidate(#candidateId)")
    public ResponseEntity<ApiResponse<CandidateResponse>> correctCandidate(
            @PathVariable String candidateId) {

        return ResponseEntity.ok(ApiResponse.success(
                candidateService.correctCandidate(candidateId), "Candidate marked as corrected"));
    }

    // ─────────────────────────────────────────────
    // POST /api/v1/candidates/{candidateId}/reject
    // Role: BUSINESS_DEVELOPMENT_MANAGER
    // ─────────────────────────────────────────────
    @PostMapping("/candidates/{candidateId}/reject")
    @PreAuthorize("hasRole('BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<ApiResponse<CandidateResponse>> rejectCandidate(
            @PathVariable String candidateId,
            @Valid @RequestBody RejectCandidateRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        return ResponseEntity.ok(ApiResponse.success(
                candidateService.rejectCandidate(candidateId, request, currentUser.getId()), "Candidate rejected"));
    }

    // ─────────────────────────────────────────────
    // POST /api/v1/candidates/{candidateId}/approve
    // Role: BUSINESS_DEVELOPMENT_MANAGER
    // ─────────────────────────────────────────────
    @PostMapping("/candidates/{candidateId}/approve")
    @PreAuthorize("hasRole('BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<ApiResponse<CandidateResponse>> approveCandidate(
            @PathVariable String candidateId,
            @RequestBody(required = false) ApproveCandidateRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        if (request == null) {
            request = new ApproveCandidateRequest();
        }

        return ResponseEntity.ok(ApiResponse.success(
                candidateService.approveCandidate(candidateId, request, currentUser.getId()), "Candidate approved"));
    }
}
