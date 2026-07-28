package com.apms.domain.user.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateUserStatusRequest {
    private Boolean enabled;
    private Boolean isActive;

    @NotNull
    public Boolean getEnabled() {
        return enabled != null ? enabled : isActive;
    }
}
