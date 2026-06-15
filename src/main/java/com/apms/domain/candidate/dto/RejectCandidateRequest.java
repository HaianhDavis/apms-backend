package com.apms.domain.candidate.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RejectCandidateRequest {
    @NotBlank(message = "rejectionReason is required")
    private String rejectionReason;
}
