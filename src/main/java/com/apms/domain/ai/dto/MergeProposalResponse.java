package com.apms.domain.ai.dto;

import com.apms.common.enums.SubmissionStatus;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class MergeProposalResponse {
    private String proposalId;
    private Map<String, Object> proposedIdentity;
    private Map<String, Object> proposedBusiness;
    private Map<String, Object> proposedContact;
    private Map<String, Object> proposedInsights;
    private List<FieldEvidence> fieldEvidence;
    private Boolean hasConflicts;
    private Integer conflictCount;
    private List<String> sourceDocumentIds;
    private List<String> importJobIds;
    private List<String> extractionIds;
    private SubmissionStatus status;
}
