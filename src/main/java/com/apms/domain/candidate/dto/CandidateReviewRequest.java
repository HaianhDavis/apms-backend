package com.apms.domain.candidate.dto;

import lombok.Data;

@Data
public class CandidateReviewRequest {
    private java.util.Map<String, FieldReviewUpdate> fields;

    @Data
    public static class FieldReviewUpdate {
        private Object reviewedValue;
        @com.fasterxml.jackson.annotation.JsonIgnore
        private boolean reviewedValuePresent;
        private com.apms.domain.ai.dto.StaffFieldReviewStatus staffReviewStatus;
        private com.apms.domain.ai.dto.ExtractionReviewStatus managerReviewStatus;
        private String staffReviewComment;
        private String managerReviewComment;
        private boolean isManager;

        public void setReviewedValue(Object reviewedValue) {
            this.reviewedValue = reviewedValue;
            this.reviewedValuePresent = true;
        }
    }
}
