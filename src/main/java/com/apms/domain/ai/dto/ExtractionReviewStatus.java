package com.apms.domain.ai.dto;

public enum ExtractionReviewStatus {
    PENDING,
    ACCEPTED,
    EDITED,
    REJECTED,
    NEEDS_REVIEW;

    @com.fasterxml.jackson.annotation.JsonCreator
    public static ExtractionReviewStatus fromJson(String value) {
        if (value == null) {
            return null;
        }
        if ("CHANGES_REQUESTED".equalsIgnoreCase(value) || "REVISION_REQUIRED".equalsIgnoreCase(value)) {
            return NEEDS_REVIEW;
        }
        return ExtractionReviewStatus.valueOf(value.trim().toUpperCase());
    }
}
