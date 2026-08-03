package com.apms.domain.contract.controller;

import com.apms.common.response.ApiResponse;
import com.apms.domain.contract.dto.SubmitPartnerContractCollectionRequest;
import com.apms.domain.contract.service.PartnerContractCollectionSubmissionService;
import com.apms.domain.project.dto.ProjectTaskSubmissionResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/tasks/{taskId}/partner-contracts/submissions")
@RequiredArgsConstructor
public class PartnerContractTaskSubmissionController {

    private final PartnerContractCollectionSubmissionService submissionService;

    @PostMapping
    @PreAuthorize("hasAnyRole('BUSINESS_DEVELOPMENT_STAFF', 'BUSINESS_DEVELOPMENT_MANAGER') and @projectSecurity.isMember(#projectId)")
    public ResponseEntity<ApiResponse<ProjectTaskSubmissionResponse>> submitCollection(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @RequestBody @Valid SubmitPartnerContractCollectionRequest request) {

        ProjectTaskSubmissionResponse response = submissionService.submitCollection(projectId, taskId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "Partner contract collection submitted successfully"));
    }
}
