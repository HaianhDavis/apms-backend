package com.apms.domain.admin.dto;

import com.apms.common.enums.SystemRole;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Set;

@Data
@Builder
public class AccountAdminResponse {
    private Long id;
    private String email;
    private String username;
    private String name;
    private String firstName;
    private String lastName;
    private String role;
    private String roleName;
    private Set<SystemRole> roles;
    private boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

