package com.apms.domain.profile.dto;

import com.apms.common.enums.ProfileVisibility;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateProfileVisibilityRequest {

    @NotNull
    private ProfileVisibility visibility;
}
