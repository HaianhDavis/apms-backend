package com.apms.domain.project.dto;

import com.apms.common.enums.TaskPriority;
import com.apms.common.enums.TaskStatus;
import com.apms.common.enums.TaskType;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UpdateProjectTaskRequest {
    private String title;
    private String description;
    private Long assignedToUserId;
    private TaskStatus status;
    private TaskPriority priority;
    private LocalDateTime dueDate;
    private TaskType taskType;
    private String targetCompanyProfileId;
}
