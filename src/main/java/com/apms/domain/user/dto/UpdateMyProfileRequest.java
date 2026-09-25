package com.apms.domain.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class UpdateMyProfileRequest {

    @NotBlank(message = "Full name cannot be blank")
    @Size(max = 100, message = "Full name cannot exceed 100 characters")
    private String fullName;
}
