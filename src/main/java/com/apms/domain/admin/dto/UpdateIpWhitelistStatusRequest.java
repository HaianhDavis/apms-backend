package com.apms.domain.admin.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateIpWhitelistStatusRequest {
    @NotNull
    private Boolean enabled;
}
