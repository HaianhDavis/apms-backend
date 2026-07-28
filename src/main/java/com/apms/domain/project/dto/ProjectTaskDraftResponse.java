package com.apms.domain.project.dto;

import com.apms.common.enums.TaskStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ProjectTaskDraftResponse {
    private Long id;
    private Long taskId;
    private String attachedCompanyProfileId;
    private String note;
    private TaskStatus status;
    private LocalDateTime updatedAt;
}
