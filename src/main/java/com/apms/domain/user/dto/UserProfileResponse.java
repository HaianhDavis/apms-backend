package com.apms.domain.user.dto;

import com.apms.common.enums.SystemRole;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Set;

@Data
@Builder
public class UserProfileResponse {
    private Long id;
    private String email;
    private String fullName;
    private Set<SystemRole> roles;
    private Boolean enabled;
    private LocalDateTime createdAt;
}
