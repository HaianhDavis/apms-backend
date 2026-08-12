package com.apms.domain.ai.controller;

import com.apms.common.response.ApiResponse;
import com.apms.domain.ai.dto.AiExtractionJobResponse;
import com.apms.domain.ai.entity.AiExtractionJob;
import com.apms.domain.ai.service.TaskExtractionOrchestrator;
import com.apms.security.UserDetailsImpl;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/tasks/{taskId}/extract-multi")
@RequiredArgsConstructor
public class TaskExtractionController {

    private final TaskExtractionOrchestrator orchestrator;

    @PostMapping
    @PreAuthorize("hasRole('BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<ApiResponse<AiExtractionJobResponse>> extractMultipleDocuments(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @Valid @RequestBody MultiDocumentExtractionRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        String jobId = orchestrator.startExtractionJob(
                projectId, taskId, request.getRawDocumentIds(), currentUser.getId());
                
        orchestrator.processExtraction(jobId, projectId, taskId, request.getRawDocumentIds(), currentUser.getId());
        
        AiExtractionJobResponse response = mapToResponse(orchestrator.getExtractionJob(jobId));
        return ResponseEntity.ok(ApiResponse.success(response, "AI extraction job started."));
    }

    @GetMapping("/{jobId}")
    @PreAuthorize("hasRole('BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<ApiResponse<AiExtractionJobResponse>> getExtractionJobStatus(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @PathVariable String jobId) {
            
        AiExtractionJob job = orchestrator.getExtractionJob(jobId);
        return ResponseEntity.ok(ApiResponse.success(mapToResponse(job), "Job status retrieved."));
    }

    private AiExtractionJobResponse mapToResponse(AiExtractionJob job) {
        return AiExtractionJobResponse.builder()
                .jobId(job.getId())
                .taskId(job.getTaskId())
                .status(job.getStatus())
                .stage(job.getStage())
                .progress(job.getProgress())
                .totalDocuments(job.getTotalDocuments())
                .processedDocuments(job.getProcessedDocuments())
                .candidateId(job.getCandidateId())
                .errorMessage(job.getErrorMessage())
                .startedAt(job.getStartedAt())
                .completedAt(job.getCompletedAt())
                .build();
    }

    @Data
    public static class MultiDocumentExtractionRequest {
        @NotEmpty
        private List<String> rawDocumentIds;
    }
}
