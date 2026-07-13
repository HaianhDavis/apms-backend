package com.apms.domain.score.controller.draft;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.apms.security.UserDetailsImpl;
import com.apms.domain.score.dto.draft.*;
import com.apms.domain.score.service.RoleEvaluationApprovalService;
import com.apms.domain.score.service.RoleEvaluationDraftService;
import com.apms.domain.score.service.RoleEvaluationSubmissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class RoleEvaluationDraftController {

    private final RoleEvaluationDraftService draftService;
    private final RoleEvaluationSubmissionService submissionService;
    private final RoleEvaluationApprovalService approvalService;

    @PostMapping("/projects/{projectId}/tasks/{taskId}/role-evaluations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public RoleEvaluationDraftResponse createDraft(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @RequestBody CreateRoleEvaluationDraftRequest request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.apms.security.UserDetailsImpl currentUser) {
        Long accountId = currentUser.getId();
        return draftService.createDraft(projectId, taskId, request, accountId);
    }

    @GetMapping("/role-evaluations/{evaluationId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF', 'MANAGER')")
    public RoleEvaluationDraftResponse getDraft(@PathVariable String evaluationId) {
        return draftService.getDraft(evaluationId);
    }

    @PatchMapping("/role-evaluations/{evaluationId}/criteria/{criterionKey}")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public RoleEvaluationDraftResponse updateCriterionInput(
            @PathVariable String evaluationId,
            @PathVariable String criterionKey,
            @RequestBody UpdateCriterionInputRequest request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.apms.security.UserDetailsImpl currentUser) {
        Long accountId = currentUser.getId();
        return draftService.updateCriterionInput(evaluationId, criterionKey, request, accountId);
    }

    @PostMapping("/role-evaluations/{evaluationId}/evidence")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public RoleEvaluationDraftResponse addEvidence(
            @PathVariable String evaluationId,
            @RequestBody CreateEvidenceRequest request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.apms.security.UserDetailsImpl currentUser) {
        Long accountId = currentUser.getId();
        return draftService.addEvidence(evaluationId, request, accountId);
    }

    @PostMapping("/role-evaluations/{evaluationId}/product-market-overlap/suggest")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public RoleEvaluationDraftResponse suggestProductMarketOverlap(
            @PathVariable String evaluationId) {
        return draftService.suggestProductMarketOverlap(evaluationId);
    }

    @PostMapping("/role-evaluations/{evaluationId}/product-market-overlap/accept")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public RoleEvaluationDraftResponse acceptAutomaticSuggestion(
            @PathVariable String evaluationId,
            @RequestBody AcceptAutomaticSuggestionRequest request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.apms.security.UserDetailsImpl currentUser) {
        Long accountId = currentUser.getId();
        return draftService.acceptAutomaticSuggestion(evaluationId, request, accountId);
    }

    @PostMapping("/role-evaluations/{evaluationId}/criteria/{criterionKey}/suggest/accept")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public RoleEvaluationDraftResponse acceptCriterionSuggestion(
            @PathVariable String evaluationId,
            @PathVariable String criterionKey,
            @RequestBody AcceptAutomaticSuggestionRequest request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.apms.security.UserDetailsImpl currentUser) {
        return draftService.acceptCriterionSuggestion(evaluationId, criterionKey, request, currentUser.getId());
    }

    @PostMapping("/role-evaluations/{evaluationId}/criteria/{criterionKey}/suggest/edit")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public RoleEvaluationDraftResponse editCriterionSuggestion(
            @PathVariable String evaluationId,
            @PathVariable String criterionKey,
            @RequestBody EditCriterionSuggestionRequest request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.apms.security.UserDetailsImpl currentUser) {
        return draftService.editCriterionSuggestion(evaluationId, criterionKey, request, currentUser.getId());
    }

    @PostMapping("/role-evaluations/{evaluationId}/criteria/{criterionKey}/suggest/reject")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public RoleEvaluationDraftResponse rejectCriterionSuggestion(
            @PathVariable String evaluationId,
            @PathVariable String criterionKey,
            @RequestBody RejectCriterionSuggestionRequest request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.apms.security.UserDetailsImpl currentUser) {
        return draftService.rejectCriterionSuggestion(evaluationId, criterionKey, request, currentUser.getId());
    }

    @PostMapping("/role-evaluations/{evaluationId}/criteria/{criterionKey}/suggest/needs-more-data")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public RoleEvaluationDraftResponse markSuggestionNeedsMoreData(
            @PathVariable String evaluationId,
            @PathVariable String criterionKey,
            @RequestBody NeedsMoreDataCriterionSuggestionRequest request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.apms.security.UserDetailsImpl currentUser) {
        return draftService.markSuggestionNeedsMoreData(evaluationId, criterionKey, request, currentUser.getId());
    }

    @PostMapping("/role-evaluations/{evaluationId}/calculate-preview")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public RoleEvaluationPreviewResponse calculatePreview(
            @PathVariable String evaluationId) {
        return draftService.calculatePreview(evaluationId);
    }

    @PostMapping("/role-evaluations/{evaluationId}/submit")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public void submitDraft(
            @PathVariable String evaluationId,
            @RequestBody SubmitRoleEvaluationRequest request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.apms.security.UserDetailsImpl currentUser) {
        Long accountId = currentUser.getId();
        submissionService.submitDraft(evaluationId, request, accountId);
    }

    @PostMapping("/role-evaluations/{evaluationId}/review")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public void reviewDraft(
            @PathVariable String evaluationId,
            @RequestBody ReviewRoleEvaluationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.apms.security.UserDetailsImpl currentUser) {
        Long accountId = currentUser.getId();
        approvalService.reviewDraft(evaluationId, request, accountId, idempotencyKey);
    }
}
