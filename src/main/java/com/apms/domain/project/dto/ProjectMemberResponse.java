package com.apms.domain.project.dto;

import com.apms.common.enums.MemberRole;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ProjectMemberResponse {

    private Long id;
    private Long accountId;
    private MemberRole memberRole;
    private LocalDateTime joinedAt;
}
