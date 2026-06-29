package com.apms.domain.ai.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class MergeCandidateResponse {
    private String candidateId;
    private Map<String, Object> identity;
    private Map<String, Object> business;
    private Map<String, Object> contact;
    private Map<String, Object> insights;
    private List<FieldEvidence> fieldEvidence;
    private Boolean hasConflicts;
    private Integer conflictCount;
    private List<String> sourceDocumentIds;
    private List<String> importJobIds;
    private List<String> extractionIds;
}
