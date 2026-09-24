package com.apms.domain.candidate.controller;

import com.apms.common.response.ApiResponse;
import com.apms.domain.candidate.dto.CandidateWorkflowResponse;
import com.apms.domain.candidate.service.CandidateWorkflowService;
import com.apms.security.UserDetailsImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/candidates")
@RequiredArgsConstructor
@Tag(name = "Candidate Workflow", description = "Centralized workflow orchestration for Candidates and Tasks")
@SecurityRequirement(name = "bearerAuth")
public class CandidateWorkflowController {

    private final CandidateWorkflowService workflowService;

    @PostMapping("/{candidateId}/workflow/submit")
    @PreAuthorize("hasRole('BUSINESS_DEVELOPMENT_STAFF')")
    @Operation(summary = "Submit Candidate for Manager Review", description = "Atomically updates Candidate, Task, and Submission states.")
    public ResponseEntity<ApiResponse<CandidateWorkflowResponse>> submitCandidate(
            @PathVariable String candidateId,
            @RequestParam Long taskId,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
            
        CandidateWorkflowResponse response = workflowService.submitCandidateForReview(candidateId, taskId, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(response, "Candidate submitted for manager review successfully"));
    }

    @PostMapping("/workflow/repair")
    @PreAuthorize("hasRole('ADMIN') or hasRole('BUSINESS_DEVELOPMENT_MANAGER')")
    @Operation(summary = "Repair Invalid State", description = "Repairs APMS-12 invalid state where Candidate is PENDING_REVIEW but Task is IN_PROGRESS")
    public ResponseEntity<ApiResponse<String>> repairInvalidState(@RequestParam Long taskId) {
        workflowService.repairInvalidState(taskId);
        return ResponseEntity.ok(ApiResponse.success("Success", "Task state repaired successfully"));
    }

    @PostMapping("/{candidateId}/workflow/manager-reject")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('BUSINESS_DEVELOPMENT_MANAGER')")
    @Operation(summary = "Manager Reject Candidate", description = "Atomically updates Candidate, Task, and Submission states.")
    public ResponseEntity<ApiResponse<CandidateWorkflowResponse>> managerRejectCandidate(
            @PathVariable String candidateId,
            @RequestParam(required = false) String comment,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
            
        CandidateWorkflowResponse response = workflowService.managerRejectCandidate(candidateId, currentUser.getId(), comment);
        return ResponseEntity.ok(ApiResponse.success(response, "Candidate rejected successfully"));
    }

    @PostMapping("/{candidateId}/workflow/manager-approve")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('BUSINESS_DEVELOPMENT_MANAGER')")
    @Operation(summary = "Manager Approve Candidate", description = "Atomically approves Candidate, Submission, Task, and creates/links the official CompanyProfile.")
    public ResponseEntity<ApiResponse<CandidateWorkflowResponse>> managerApproveCandidate(
            @PathVariable String candidateId,
            @RequestParam(required = false) String comment,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        CandidateWorkflowResponse response = workflowService.managerApproveCandidate(candidateId, currentUser.getId(), comment);
        return ResponseEntity.ok(ApiResponse.success(response, "Candidate approved successfully"));
    }

    @PostMapping("/{candidateId}/workflow/reconcile-send-back")
    @PreAuthorize("hasRole('ADMIN') or hasRole('BUSINESS_DEVELOPMENT_MANAGER')")
    @Operation(summary = "Reconcile Send Back State", description = "Repairs state where Candidate is REVISION_REQUIRED but Task is IN_REVIEW")
    public ResponseEntity<ApiResponse<String>> reconcileSendBack(@PathVariable String candidateId) {
        workflowService.reconcileSendBackInconsistency(candidateId);
        return ResponseEntity.ok(ApiResponse.success("Success", "Send back state reconciled successfully"));
    }
}
