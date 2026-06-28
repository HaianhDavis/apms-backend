package com.apms.domain.user.dto;

import com.apms.common.enums.SystemRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.Set;

@Data
public class CreateUserRequest {
    @NotBlank
    @Email
    private String email;

    @NotBlank
    private String fullName;

    @NotBlank
    private String password;

    @NotEmpty
    private Set<SystemRole> roles;

    private Boolean enabled = true;
}
