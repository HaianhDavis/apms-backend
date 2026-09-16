package com.apms.domain.profile.assessment.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RequestChangesAssessmentRequest {
    @NotBlank(message = "Reason for requesting changes is required")
    private String reason;
}
