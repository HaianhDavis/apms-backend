package com.apms.domain.profile;

import com.apms.common.enums.SubmissionStatus;
import com.apms.domain.ai.dto.FieldEvidence;
import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@Document(collection = "company_profile_update_proposals")
public class CompanyProfileUpdateProposal {

    @Id
    private String id;
    
    private Long projectId;
    private Long taskId;
    
    private String companyProfileId;
    
    private Map<String, Object> proposedIdentity;
    private Map<String, Object> proposedBusiness;
    private Map<String, Object> proposedContact;
    private Map<String, Object> proposedInsights;
    private Map<String, Object> proposedFinancial;
    private Map<String, Object> proposedMarket;
    private Map<String, Object> proposedInnovation;
    private Map<String, Object> proposedRisk;
    private Map<String, Object> proposedCompliance;
    
    private List<String> sourceDocumentIds;
    private String extractionId;        // Legacy: single extraction ID
    private List<String> extractionIds; // Multi-extraction merge IDs
    private List<FieldEvidence> fieldEvidence;
    private Boolean hasConflicts;
    private Integer conflictCount;
    private SubmissionStatus status;
    
    private Long submittedBy;
    private Long reviewedBy;
    private String reviewComment;
    private String changeSummary;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}
