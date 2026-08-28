package com.apms.domain.project.dto;

import com.apms.common.enums.ProjectRole;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateMemberRoleRequest {

    @NotNull(message = "projectRole is required")
    private ProjectRole projectRole;
}
