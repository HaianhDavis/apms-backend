package com.apms.domain.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class IpWhitelistEntryRequest {
    @NotBlank
    private String ipAddress;
    private String description;
    @NotNull
    private Boolean enabled = true;
}
