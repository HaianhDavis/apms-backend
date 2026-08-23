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

    private Integer revisionNumber;

    private String companyProfileId;
    private Map<String, Object> proposedIdentity;
    private Map<String, Object> proposedBusiness;
    private Map<String, Object> proposedCompanySize;
    private Map<String, Object> proposedContact;
    private Map<String, Object> proposedInsights;
    private Map<String, Object> proposedFinancial;
    private Map<String, Object> proposedMarket;
    private Map<String, Object> proposedInnovation;
    private Map<String, Object> proposedRisk;
    private Map<String, Object> proposedCompliance;
    private List<Map<String, Object>> proposedCompanyMembers;
    private String proposedRelationship;

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
