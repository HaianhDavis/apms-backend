package com.apms.domain.user.dto;

import com.apms.common.enums.SystemRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileResponse {
    private Long id;
    private String email;
    private String fullName;
    private Set<SystemRole> roles;
    private Boolean enabled;
    private Boolean emailVerified;
    private LocalDateTime createdAt;
    private String phone;
    private String department;
    private String bio;
    private String address;
}
