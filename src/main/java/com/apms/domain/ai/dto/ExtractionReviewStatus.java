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
        if ("CHANGES_REQUESTED".equalsIgnoreCase(value) || "REQUEST_CHANGES".equalsIgnoreCase(value) || "REVISION_REQUIRED".equalsIgnoreCase(value)) {
            return NEEDS_REVIEW;
        }
        if ("APPROVED".equalsIgnoreCase(value)) {
            return ACCEPTED;
        }
        if ("PENDING_REVIEW".equalsIgnoreCase(value) || "RESET".equalsIgnoreCase(value) || "UNDO".equalsIgnoreCase(value)) {
            return PENDING;
        }
        return ExtractionReviewStatus.valueOf(value.trim().toUpperCase());
    }
}
