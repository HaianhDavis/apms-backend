package com.apms.domain.project.dto;

import com.apms.common.enums.TaskPriority;
import com.apms.common.enums.TaskStatus;
import com.apms.common.enums.TaskType;
import com.apms.common.enums.TaskAction;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ProjectTaskResponse {
    private Long id;
    private Long projectId;
    private String title;
    private String description;
    private Long assignedToUserId;
    private String assignedToName;
    private String assignedToEmail;
    private Long createdByUserId;
    private TaskStatus status;
    private TaskPriority priority;
    private LocalDateTime dueDate;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
    private TaskType taskType;
    private ProjectKeyResultResponse keyResult;
    private String targetCompanyProfileId;
    private java.util.List<TaskAction> availableActions;
}
