package com.apms.domain.profile.dto;

import com.apms.common.enums.SubmissionStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class CompanyProfileUpdateProposalResponse {
    private String id;
    private Long projectId;
    private Long taskId;
    private String companyProfileId;
    private Map<String, Object> proposedIdentity;
    private Map<String, Object> proposedBusiness;
    private Map<String, Object> proposedContact;
    private Map<String, Object> proposedInsights;
    private List<String> sourceDocumentIds;
    private String extractionId;
    private SubmissionStatus status;
    private Long submittedBy;
    private Long reviewedBy;
    private String reviewComment;
    private String changeSummary;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
