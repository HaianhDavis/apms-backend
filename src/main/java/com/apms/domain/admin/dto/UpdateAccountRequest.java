package com.apms.domain.admin.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateAccountRequest {
    @Size(min = 1, max = 100)
    private String name;

    @Email
    private String email;

    @Size(min = 1, max = 50)
    private String username;

    @Size(min = 8, max = 100)
    private String password;

    private String role;
    private Boolean active;
}
