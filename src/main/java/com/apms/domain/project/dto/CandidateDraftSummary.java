package com.apms.domain.project.dto;

import com.apms.common.enums.CandidateStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Lightweight summary of a CompanyCandidate draft for workbench display.
 * Staff generates multiple drafts from selected extraction subsets;
 * this DTO exposes the key metadata needed for the task detail screen.
 */
@Data
@Builder
public class CandidateDraftSummary {
    private String candidateId;
    private CandidateStatus status;
    private Long taskId;
    private List<String> extractionIds;
    private List<String> sourceDocumentIds;
    private LocalDateTime createdAt;
    private Boolean hasConflicts;
    private Integer conflictCount;
    /** True if this draft is linked to an IN_REVIEW or SUBMITTED submission */
    private Boolean isUnderReview;
    /** True if this draft is linked to an APPROVED submission */
    private Boolean isApproved;
    /** The submission ID that references this draft, if any */
    private Long linkedSubmissionId;
}
