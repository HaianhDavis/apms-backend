package com.apms.domain.ai.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ExtractionReviewRequest {
    private StaffFieldReviewStatus staffReviewStatus;
    private ExtractionReviewStatus managerReviewStatus;
    private Object reviewedValue;
    private String comment;
    private boolean isManager; // Flag to indicate if the reviewer is a manager
}
