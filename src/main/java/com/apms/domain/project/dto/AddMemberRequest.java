package com.apms.domain.project.dto;

import com.apms.common.enums.MemberRole;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AddMemberRequest {

    @NotNull(message = "accountId is required")
    private Long accountId;

    @NotNull(message = "memberRole is required")
    private MemberRole memberRole;
}
