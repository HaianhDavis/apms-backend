package com.apms.domain.profile.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferResponsibilityRequest {

    @NotNull(message = "New manager account ID is required")
    private Long newManagerAccountId;

    @NotBlank(message = "Reason for transfer is required")
    private String reason;

    private Long expectedCurrentManagerAccountId;
}
