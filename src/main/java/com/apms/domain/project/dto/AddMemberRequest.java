package com.apms.domain.project.dto;

import com.apms.common.enums.MemberRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AddMemberRequest {

    private Long accountId;

    @Email(message = "email must be valid")
    private String email;

    @NotNull(message = "memberRole is required")
    private MemberRole memberRole;
}
