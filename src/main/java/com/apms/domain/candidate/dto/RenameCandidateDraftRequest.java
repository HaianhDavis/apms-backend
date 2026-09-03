package com.apms.domain.candidate.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RenameCandidateDraftRequest {

    @NotBlank(message = "Draft name cannot be blank")
    @Size(max = 200, message = "Draft name cannot exceed 200 characters")
    private String draftName;
}
