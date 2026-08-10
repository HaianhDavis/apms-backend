package com.apms.domain.project.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class ReviewSummaryResponse {
    private Integer revisionNumber;
    private Long documentVersion;
    private Integer submittedRevisionNumber;

    private Map<String, FieldApprovalResponse> fieldApprovals;
    private List<String> changedFieldPaths;
    
    private List<String> reviewQueue;
    private List<String> previouslyApprovedFields;
    
    private boolean readyForApproval;
    private List<String> blockingFields;
    private List<String> staleFields;
}
