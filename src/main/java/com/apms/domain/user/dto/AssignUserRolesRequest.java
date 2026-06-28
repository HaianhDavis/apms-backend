package com.apms.domain.user.dto;

import com.apms.common.enums.SystemRole;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.Set;

@Data
public class AssignUserRolesRequest {
    @NotEmpty
    private Set<SystemRole> roles;
}
