package com.apms.domain.ai.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ExtractionReviewRequest {
    @NotNull
    private ExtractionReviewStatus reviewStatus;
    private Object reviewedValue;
    private String comment;
}
