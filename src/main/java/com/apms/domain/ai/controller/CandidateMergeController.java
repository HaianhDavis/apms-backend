package com.apms.domain.ai.controller;

import com.apms.domain.ai.dto.MergeCandidateResponse;
import com.apms.domain.ai.dto.MergeExtractionsIntoCandidateRequest;
import com.apms.domain.ai.service.ExtractionMergeService;
import com.apms.security.UserDetailsImpl;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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
     * Merges multiple AI extraction results into a single DRAFT CompanyCandidate.
     * DOES NOT submit the task — Staff must separately submit via the task submission endpoint.
     */
    @PostMapping("/from-extractions")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<MergeCandidateResponse> mergeExtractionsIntoCandidate(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @Valid @RequestBody MergeExtractionsIntoCandidateRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        MergeCandidateResponse response = mergeService.mergeExtractionsIntoCandidate(
                projectId, taskId, request.getExtractionIds(), request.getNote(), currentUser.getId());

        return ResponseEntity.ok(response);
    }
}
