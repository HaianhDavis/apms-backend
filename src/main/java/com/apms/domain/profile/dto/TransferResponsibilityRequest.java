package com.apms.domain.profile.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TransferResponsibilityRequest {
    @NotNull(message = "Manager ID is required")
    private Long managerId;
}
