package com.apms.domain.profile.assessment.controller;

import com.apms.domain.profile.assessment.dto.*;
import com.apms.domain.profile.assessment.service.CompanyRelationshipAssessmentService;
import com.apms.security.UserDetailsImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Tag(name = "Relationship Closeness Assessment", description = "Structured 0-100 Relationship Closeness Score V1")
public class CompanyRelationshipAssessmentController {

    private final CompanyRelationshipAssessmentService assessmentService;

    @GetMapping("/api/v1/company-profiles/{companyProfileId}/relationship-assessments/overview")
    @Operation(summary = "Get current active assessment, official score, and live preview evidence")
    public ResponseEntity<Map<String, Object>> getOverview(
            @PathVariable String companyProfileId,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        return ResponseEntity.ok(assessmentService.getOverview(companyProfileId, currentUser));
    }

    @GetMapping("/api/v1/company-profiles/{companyProfileId}/relationship-assessments/history")
    @Operation(summary = "Get finalized assessment history")
    public ResponseEntity<List<RelationshipAssessmentResponse>> getHistory(
            @PathVariable String companyProfileId,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        return ResponseEntity.ok(assessmentService.getHistory(companyProfileId, currentUser));
    }

    @GetMapping("/api/v1/company-profiles/{companyProfileId}/relationship-assessments/commercial-evidence")
    @Operation(summary = "Get live commercial evidence calculated from approved contracts")
    public ResponseEntity<CommercialEvidenceResponse> getCommercialEvidence(
            @PathVariable String companyProfileId) {
        return ResponseEntity.ok(assessmentService.getLiveCommercialEvidence(companyProfileId));
    }

    @PostMapping("/api/v1/company-profiles/{companyProfileId}/relationship-assessments")
    @Operation(summary = "Create initial draft assessment")
    public ResponseEntity<RelationshipAssessmentResponse> createDraft(
            @PathVariable String companyProfileId,
            @RequestBody(required = false) CreateRelationshipAssessmentRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        return ResponseEntity.ok(assessmentService.createDraft(companyProfileId, request, currentUser));
    }

    @GetMapping("/api/v1/relationship-assessments/{assessmentId}")
    @Operation(summary = "Get a specific relationship assessment by ID")
    public ResponseEntity<RelationshipAssessmentResponse> getAssessmentById(
            @PathVariable Long assessmentId,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        return ResponseEntity.ok(assessmentService.getAssessmentById(assessmentId, currentUser));
    }

    @PutMapping("/api/v1/relationship-assessments/{assessmentId}")
    @Operation(summary = "Update draft or changes-requested assessment")
    public ResponseEntity<RelationshipAssessmentResponse> updateDraft(
            @PathVariable Long assessmentId,
            @RequestBody(required = false) UpdateRelationshipAssessmentRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        return ResponseEntity.ok(assessmentService.updateDraft(assessmentId, request, currentUser));
    }

    @PostMapping("/api/v1/relationship-assessments/{assessmentId}/submit")
    @Operation(summary = "Manager submits assessment to Owner")
    public ResponseEntity<RelationshipAssessmentResponse> submitAssessment(
            @PathVariable Long assessmentId,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        return ResponseEntity.ok(assessmentService.submitAssessment(assessmentId, currentUser));
    }

    @PostMapping("/api/v1/relationship-assessments/{assessmentId}/complete")
    @Operation(summary = "Manager directly completes relationship closeness assessment")
    public ResponseEntity<RelationshipAssessmentResponse> completeAssessment(
            @PathVariable Long assessmentId,
            @RequestBody(required = false) UpdateRelationshipAssessmentRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        return ResponseEntity.ok(assessmentService.completeAssessment(assessmentId, request, currentUser));
    }

    @PostMapping("/api/v1/relationship-assessments/{assessmentId}/request-changes")
    @Operation(summary = "Owner requests changes on submitted assessment")
    public ResponseEntity<RelationshipAssessmentResponse> requestChanges(
            @PathVariable Long assessmentId,
            @Valid @RequestBody RequestChangesAssessmentRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        return ResponseEntity.ok(assessmentService.requestChanges(assessmentId, request, currentUser));
    }

    @PostMapping("/api/v1/relationship-assessments/{assessmentId}/finalize")
    @Operation(summary = "Owner finalizes relationship closeness score")
    public ResponseEntity<RelationshipAssessmentResponse> finalizeAssessment(
            @PathVariable Long assessmentId,
            @RequestBody(required = false) FinalizeRelationshipAssessmentRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        return ResponseEntity.ok(assessmentService.finalizeAssessment(assessmentId, request, currentUser));
    }

    @PostMapping("/api/v1/company-profiles/{companyProfileId}/relationship-assessments/new-version")
    @Operation(summary = "Start a new assessment version after previous finalization")
    public ResponseEntity<RelationshipAssessmentResponse> createNewVersion(
            @PathVariable String companyProfileId,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        return ResponseEntity.ok(assessmentService.createNewVersion(companyProfileId, currentUser));
    }

    @PostMapping("/api/v1/relationship-assessments/{assessmentId}/owner-adjustment")
    @Operation(summary = "Create Owner Adjustment from the latest official finalized assessment")
    public ResponseEntity<RelationshipAssessmentResponse> createOwnerAdjustment(
            @PathVariable Long assessmentId,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        return ResponseEntity.ok(assessmentService.createOwnerAdjustment(assessmentId, currentUser));
    }

    @PutMapping("/api/v1/relationship-assessments/{assessmentId}/owner-adjustment")
    @Operation(summary = "Update Owner Adjustment draft")
    public ResponseEntity<RelationshipAssessmentResponse> updateOwnerAdjustment(
            @PathVariable Long assessmentId,
            @RequestBody(required = false) OwnerAdjustmentUpdateRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        return ResponseEntity.ok(assessmentService.updateOwnerAdjustment(assessmentId, request, currentUser));
    }

    @PostMapping("/api/v1/relationship-assessments/{assessmentId}/complete-owner-adjustment")
    @Operation(summary = "Complete and finalize Owner Adjustment")
    public ResponseEntity<RelationshipAssessmentResponse> completeOwnerAdjustment(
            @PathVariable Long assessmentId,
            @RequestBody(required = false) OwnerAdjustmentUpdateRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        return ResponseEntity.ok(assessmentService.completeOwnerAdjustment(assessmentId, request, currentUser));
    }

    @PostMapping("/api/v1/relationship-assessments/{assessmentId}/cancel-owner-adjustment")
    @Operation(summary = "Cancel Owner Adjustment draft")
    public ResponseEntity<RelationshipAssessmentResponse> cancelOwnerAdjustment(
            @PathVariable Long assessmentId,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        return ResponseEntity.ok(assessmentService.cancelOwnerAdjustment(assessmentId, currentUser));
    }
}
