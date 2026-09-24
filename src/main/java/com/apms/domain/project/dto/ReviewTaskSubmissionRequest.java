package com.apms.domain.project.dto;

import com.apms.common.enums.ReviewDecision;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ReviewTaskSubmissionRequest {
    @NotNull
    private ReviewDecision decision;

    private String comment;
}
