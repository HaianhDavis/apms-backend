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
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class RoleEvaluationDraftController {

    private final RoleEvaluationDraftService draftService;
    private final RoleEvaluationSubmissionService submissionService;
    private final RoleEvaluationApprovalService approvalService;
    private final com.apms.domain.score.service.CompetitorSuggestionGenerationService suggestionGenerationService;
    private final com.apms.domain.score.service.PartnerSuggestionGenerationService partnerSuggestionGenerationService;
    private final com.apms.domain.score.service.PartnerDataSufficiencyEvaluator dataSufficiencyEvaluator;
    private final com.apms.domain.score.service.PotentialPartnerDataSufficiencyEvaluator potentialPartnerDataSufficiencyEvaluator;
    private final com.apms.domain.score.service.PartnerSuggestionReviewService partnerSuggestionReviewService;
    private final com.apms.domain.score.service.RoleEvaluationSecurityService securityService;

    @PostMapping("/projects/{projectId}/tasks/{taskId}/role-evaluations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'BUSINESS_DEVELOPMENT_STAFF', 'RESEARCH_STAFF')")
    public RoleEvaluationDraftResponse createDraft(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @RequestBody CreateRoleEvaluationDraftRequest request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.apms.security.UserDetailsImpl currentUser) {
        Long accountId = currentUser.getId();
        return draftService.createDraft(projectId, taskId, request, accountId);
    }

    @GetMapping("/projects/{projectId}/tasks/{taskId}/role-evaluations")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'BUSINESS_DEVELOPMENT_STAFF', 'RESEARCH_STAFF', 'BUSINESS_DEVELOPMENT_MANAGER')")
    public java.util.List<RoleEvaluationDraftResponse> getTaskDrafts(
            @PathVariable Long projectId,
            @PathVariable Long taskId) {
        return draftService.getDraftsForTask(projectId, taskId);
    }

    @GetMapping("/role-evaluations/{evaluationId}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'BUSINESS_DEVELOPMENT_STAFF', 'RESEARCH_STAFF', 'BUSINESS_DEVELOPMENT_MANAGER')")
    public RoleEvaluationDraftResponse getDraft(@PathVariable String evaluationId) {
        return draftService.getDraft(evaluationId);
    }

    @PatchMapping("/role-evaluations/{evaluationId}/criteria/{criterionKey}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'BUSINESS_DEVELOPMENT_STAFF', 'RESEARCH_STAFF')")
    public RoleEvaluationDraftResponse updateCriterionInput(
            @PathVariable String evaluationId,
            @PathVariable String criterionKey,
            @RequestBody UpdateCriterionInputRequest request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.apms.security.UserDetailsImpl currentUser) {
        Long accountId = currentUser.getId();
        return draftService.updateCriterionInput(evaluationId, criterionKey, request, accountId);
    }

    @PostMapping("/role-evaluations/{evaluationId}/evidence")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'BUSINESS_DEVELOPMENT_STAFF', 'RESEARCH_STAFF')")
    public RoleEvaluationDraftResponse addEvidence(
            @PathVariable String evaluationId,
            @RequestBody CreateEvidenceRequest request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.apms.security.UserDetailsImpl currentUser) {
        Long accountId = currentUser.getId();
        return draftService.addEvidence(evaluationId, request, accountId);
    }

    @DeleteMapping("/role-evaluations/{evaluationId}/evidence/{evidenceId}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'BUSINESS_DEVELOPMENT_STAFF', 'RESEARCH_STAFF')")
    public RoleEvaluationDraftResponse removeEvidence(
            @PathVariable String evaluationId,
            @PathVariable String evidenceId,
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.apms.security.UserDetailsImpl currentUser) {
        Long accountId = currentUser.getId();
        return draftService.removeEvidence(evaluationId, evidenceId, accountId);
    }

    @PostMapping("/role-evaluations/{evaluationId}/suggestions/generate")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'BUSINESS_DEVELOPMENT_STAFF', 'RESEARCH_STAFF')")
    public BatchGenerationResponse generateSuggestions(
            @PathVariable String evaluationId,
            @RequestBody(required = false) GenerateSuggestionRequest request) {
        com.apms.domain.score.draft.RoleEvaluationDraft draft = draftService.getRawDraft(evaluationId);
        java.util.Map<String, String> outcomes = suggestionGenerationService.generateAll(draft, request);
        return BatchGenerationResponse.builder()
                .draft(draftService.getDraft(evaluationId))
                .outcomes(outcomes)
                .build();
    }

    @PostMapping("/role-evaluations/{evaluationId}/criteria/{criterionKey}/suggest")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'BUSINESS_DEVELOPMENT_STAFF', 'RESEARCH_STAFF')")
    public SingleGenerationResponse generateCriterionSuggestion(
            @PathVariable String evaluationId,
            @PathVariable String criterionKey,
            @RequestBody(required = false) GenerateSuggestionRequest request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.apms.security.UserDetailsImpl currentUser) {
        com.apms.domain.score.draft.RoleEvaluationDraft draft = draftService.getRawDraft(evaluationId);

        String outcome;
        if (draft.getEvaluatedRole() == com.apms.domain.company.enums.CompanyRole.PARTNER ||
            draft.getEvaluatedRole() == com.apms.domain.company.enums.CompanyRole.POTENTIAL_PARTNER) {
            securityService.canAccessDraft(evaluationId, currentUser.getId());
            String generationId = request != null && request.getGenerationId() != null ? request.getGenerationId() : java.util.UUID.randomUUID().toString();
            outcome = partnerSuggestionGenerationService.generateSuggestion(evaluationId, criterionKey, generationId);
        } else {
            outcome = suggestionGenerationService.generateSingleAndSave(draft, criterionKey, request);
        }

        return SingleGenerationResponse.builder()
                .draft(draftService.getDraft(evaluationId))
                .outcome(outcome)
                .build();
    }

    @GetMapping("/role-evaluations/{evaluationId}/readiness")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'BUSINESS_DEVELOPMENT_STAFF', 'RESEARCH_STAFF', 'BUSINESS_DEVELOPMENT_MANAGER')")
    public com.apms.domain.score.dto.draft.RoleEvaluationReadinessResponse checkReadiness(
            @PathVariable String evaluationId) {
        com.apms.domain.score.draft.RoleEvaluationDraft draft = draftService.getRawDraft(evaluationId);
        if (draft.getEvaluatedRole() == com.apms.domain.company.enums.CompanyRole.PARTNER) {
            securityService.canAccessDraft(evaluationId, 1L); // mocked ID
            return dataSufficiencyEvaluator.evaluate(draft);
        }
        if (draft.getEvaluatedRole() == com.apms.domain.company.enums.CompanyRole.POTENTIAL_PARTNER) {
            securityService.canAccessDraft(evaluationId, 1L);
            return potentialPartnerDataSufficiencyEvaluator.evaluate(draft);
        }
        throw new com.apms.common.exception.BusinessValidationException("Readiness endpoint only supported for PARTNER and POTENTIAL_PARTNER");
    }

    @PostMapping("/role-evaluations/{evaluationId}/product-market-overlap/suggest")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'BUSINESS_DEVELOPMENT_STAFF', 'RESEARCH_STAFF')")
    public RoleEvaluationDraftResponse suggestProductMarketOverlap(
            @PathVariable String evaluationId) {
        return draftService.suggestProductMarketOverlap(evaluationId);
    }

    @PostMapping("/role-evaluations/{evaluationId}/product-market-overlap/accept")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'BUSINESS_DEVELOPMENT_STAFF', 'RESEARCH_STAFF')")
    public RoleEvaluationDraftResponse acceptAutomaticSuggestion(
            @PathVariable String evaluationId,
            @RequestBody AcceptAutomaticSuggestionRequest request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.apms.security.UserDetailsImpl currentUser) {
        Long accountId = currentUser.getId();
        return draftService.acceptAutomaticSuggestion(evaluationId, request, accountId);
    }

    @PostMapping("/role-evaluations/{evaluationId}/criteria/{criterionKey}/suggest/accept")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'BUSINESS_DEVELOPMENT_STAFF', 'RESEARCH_STAFF')")
    public RoleEvaluationDraftResponse acceptCriterionSuggestion(
            @PathVariable String evaluationId,
            @PathVariable String criterionKey,
            @RequestBody AcceptAutomaticSuggestionRequest request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.apms.security.UserDetailsImpl currentUser) {
        com.apms.domain.score.draft.RoleEvaluationDraft draft = draftService.getRawDraft(evaluationId);
        if (draft.getEvaluatedRole() == com.apms.domain.company.enums.CompanyRole.PARTNER ||
            draft.getEvaluatedRole() == com.apms.domain.company.enums.CompanyRole.POTENTIAL_PARTNER) {
            PartnerSuggestionReviewRequest reviewReq = new PartnerSuggestionReviewRequest();
            reviewReq.setStatus(com.apms.domain.score.enums.CriterionSuggestionReviewStatus.ACCEPTED);
            partnerSuggestionReviewService.reviewSuggestion(evaluationId, criterionKey, reviewReq, currentUser.getId());
            return draftService.getDraft(evaluationId);
        }
        return draftService.acceptCriterionSuggestion(evaluationId, criterionKey, request, currentUser.getId());
    }

    @PostMapping("/role-evaluations/{evaluationId}/criteria/{criterionKey}/suggest/edit")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'BUSINESS_DEVELOPMENT_STAFF', 'RESEARCH_STAFF')")
    public RoleEvaluationDraftResponse editCriterionSuggestion(
            @PathVariable String evaluationId,
            @PathVariable String criterionKey,
            @RequestBody EditCriterionSuggestionRequest request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.apms.security.UserDetailsImpl currentUser) {
        com.apms.domain.score.draft.RoleEvaluationDraft draft = draftService.getRawDraft(evaluationId);
        if (draft.getEvaluatedRole() == com.apms.domain.company.enums.CompanyRole.PARTNER ||
            draft.getEvaluatedRole() == com.apms.domain.company.enums.CompanyRole.POTENTIAL_PARTNER) {
            PartnerSuggestionReviewRequest reviewReq = new PartnerSuggestionReviewRequest();
            reviewReq.setStatus(com.apms.domain.score.enums.CriterionSuggestionReviewStatus.EDITED);
            reviewReq.setEditedRationale(request.getOverrideReason());
            partnerSuggestionReviewService.reviewSuggestion(evaluationId, criterionKey, reviewReq, currentUser.getId());
            return draftService.getDraft(evaluationId);
        }
        return draftService.editCriterionSuggestion(evaluationId, criterionKey, request, currentUser.getId());
    }

    @PostMapping("/role-evaluations/{evaluationId}/criteria/{criterionKey}/suggest/reject")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'BUSINESS_DEVELOPMENT_STAFF', 'RESEARCH_STAFF')")
    public RoleEvaluationDraftResponse rejectCriterionSuggestion(
            @PathVariable String evaluationId,
            @PathVariable String criterionKey,
            @RequestBody RejectCriterionSuggestionRequest request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.apms.security.UserDetailsImpl currentUser) {
        com.apms.domain.score.draft.RoleEvaluationDraft draft = draftService.getRawDraft(evaluationId);
        if (draft.getEvaluatedRole() == com.apms.domain.company.enums.CompanyRole.PARTNER ||
            draft.getEvaluatedRole() == com.apms.domain.company.enums.CompanyRole.POTENTIAL_PARTNER) {
            PartnerSuggestionReviewRequest reviewReq = new PartnerSuggestionReviewRequest();
            reviewReq.setStatus(com.apms.domain.score.enums.CriterionSuggestionReviewStatus.REJECTED);
            partnerSuggestionReviewService.reviewSuggestion(evaluationId, criterionKey, reviewReq, currentUser.getId());
            return draftService.getDraft(evaluationId);
        }
        return draftService.rejectCriterionSuggestion(evaluationId, criterionKey, request, currentUser.getId());
    }

    @PostMapping("/role-evaluations/{evaluationId}/criteria/{criterionKey}/suggest/needs-more-data")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'BUSINESS_DEVELOPMENT_STAFF', 'RESEARCH_STAFF')")
    public RoleEvaluationDraftResponse markSuggestionNeedsMoreData(
            @PathVariable String evaluationId,
            @PathVariable String criterionKey,
            @RequestBody NeedsMoreDataCriterionSuggestionRequest request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.apms.security.UserDetailsImpl currentUser) {
        com.apms.domain.score.draft.RoleEvaluationDraft draft = draftService.getRawDraft(evaluationId);
        if (draft.getEvaluatedRole() == com.apms.domain.company.enums.CompanyRole.PARTNER ||
            draft.getEvaluatedRole() == com.apms.domain.company.enums.CompanyRole.POTENTIAL_PARTNER) {
            PartnerSuggestionReviewRequest reviewReq = new PartnerSuggestionReviewRequest();
            reviewReq.setStatus(com.apms.domain.score.enums.CriterionSuggestionReviewStatus.NEEDS_MORE_DATA);
            partnerSuggestionReviewService.reviewSuggestion(evaluationId, criterionKey, reviewReq, currentUser.getId());
            return draftService.getDraft(evaluationId);
        }
        return draftService.markSuggestionNeedsMoreData(evaluationId, criterionKey, request, currentUser.getId());
    }

    @PostMapping("/role-evaluations/{evaluationId}/calculate-preview")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'BUSINESS_DEVELOPMENT_STAFF', 'RESEARCH_STAFF')")
    public RoleEvaluationPreviewResponse calculatePreview(
            @PathVariable String evaluationId) {
        return draftService.calculatePreview(evaluationId);
    }

    @PostMapping("/role-evaluations/{evaluationId}/submit")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'BUSINESS_DEVELOPMENT_STAFF', 'RESEARCH_STAFF')")
    public void submitDraft(
            @PathVariable String evaluationId,
            @RequestBody SubmitRoleEvaluationRequest request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.apms.security.UserDetailsImpl currentUser) {
        Long accountId = currentUser.getId();
        submissionService.submitDraft(evaluationId, request, accountId);
    }

    @PostMapping("/role-evaluations/{evaluationId}/review")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'BUSINESS_DEVELOPMENT_MANAGER')")
    public org.springframework.http.ResponseEntity<?> reviewDraft(
            @PathVariable String evaluationId,
            @RequestBody ReviewRoleEvaluationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.apms.security.UserDetailsImpl currentUser) {
        Long accountId = currentUser.getId();
        approvalService.reviewDraft(evaluationId, request, accountId, idempotencyKey);

        com.apms.domain.score.draft.RoleEvaluationDraft updatedDraft = draftService.getRawDraft(evaluationId);
        if (updatedDraft.getStatus() == com.apms.domain.score.enums.RoleEvaluationStatus.APPROVAL_PROCESSING) {
            java.util.Map<String, Object> response = new java.util.HashMap<>();
            response.put("status", updatedDraft.getStatus().name());
            response.put("evaluationId", evaluationId);
            response.put("approvedVersionId", updatedDraft.getCurrentApprovedVersionId());
            response.put("approvedVersionNumber", updatedDraft.getCurrentApprovedVersionNumber());
            return org.springframework.http.ResponseEntity.accepted().body(response);
        }
        return org.springframework.http.ResponseEntity.noContent().build();
    }
}
