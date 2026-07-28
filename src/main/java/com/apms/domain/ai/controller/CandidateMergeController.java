package com.apms.domain.ai.controller;

import com.apms.domain.ai.dto.MergeCandidateResponse;
import com.apms.domain.ai.dto.MergeExtractionsIntoCandidateRequest;
import com.apms.domain.ai.service.ExtractionMergeService;
import com.apms.security.UserDetailsImpl;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/tasks/{taskId}/candidates")
@RequiredArgsConstructor
public class CandidateMergeController {

    private final ExtractionMergeService mergeService;

    /**
     * POST /api/v1/projects/{projectId}/tasks/{taskId}/candidates/from-extractions
     *
     * Generates a DRAFT CompanyCandidate from the selected extraction results.
     * Staff may call this multiple times with different extraction subsets
     * to create multiple independent drafts as reviewable alternatives.
     * The generated draft is NOT submitted — Staff must separately choose
     * one draft and submit it via the task submission endpoint.
     */
    @PostMapping("/from-extractions")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF', 'KEY_MEMBER')")
    public ResponseEntity<MergeCandidateResponse> mergeExtractionsIntoCandidate(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @Valid @RequestBody MergeExtractionsIntoCandidateRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        if (currentUser == null) {
            throw new AccessDeniedException("Authentication required");
        }
        MergeCandidateResponse response = mergeService.mergeExtractionsIntoCandidate(
                projectId, taskId, request.getExtractionIds(), request.getNote(), currentUser.getId());

        return ResponseEntity.ok(response);
    }
}
