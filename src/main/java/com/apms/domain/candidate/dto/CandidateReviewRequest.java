package com.apms.domain.candidate.dto;

import lombok.Data;

@Data
public class CandidateReviewRequest {
    private java.util.Map<String, FieldReviewUpdate> fields;

    @Data
    public static class FieldReviewUpdate {
        private Object reviewedValue;
        private com.apms.domain.ai.dto.ExtractionReviewStatus reviewStatus;
    }
}
