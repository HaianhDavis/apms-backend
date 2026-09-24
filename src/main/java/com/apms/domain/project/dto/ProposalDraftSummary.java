package com.apms.domain.project.dto;

import com.apms.common.enums.SubmissionStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Lightweight summary of a CompanyProfileUpdateProposal draft for workbench display.
 * Staff generates multiple proposal drafts from selected extraction subsets;
 * this DTO exposes the key metadata needed for the task detail screen.
 */
@Data
@Builder
public class ProposalDraftSummary {
    private String proposalId;
    private SubmissionStatus status;
    private Long taskId;
    private String companyProfileId;
    private List<String> extractionIds;
    private List<String> sourceDocumentIds;
    private LocalDateTime createdAt;
    private Boolean hasConflicts;
    private Integer conflictCount;
    private String changeSummary;
    /** True if this draft is linked to an IN_REVIEW or SUBMITTED submission */
    private Boolean isUnderReview;
    /** True if this draft is linked to an APPROVED submission */
    private Boolean isApproved;
    /** The submission ID that references this draft, if any */
    private Long linkedSubmissionId;
}
