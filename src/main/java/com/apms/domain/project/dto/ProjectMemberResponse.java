package com.apms.domain.project.dto;

import com.apms.common.enums.ProjectRole;
import com.apms.common.enums.SystemRole;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ProjectMemberResponse {

    private Long id;
    private Long accountId;
    private String email;
    private String fullName;
    private SystemRole accountRole;
    private ProjectRole projectRole;
    private LocalDateTime joinedAt;
}
