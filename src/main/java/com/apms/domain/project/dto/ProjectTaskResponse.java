package com.apms.domain.project.dto;

import com.apms.common.enums.TaskPriority;
import com.apms.common.enums.TaskStatus;
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
    private Long createdByUserId;
    private TaskStatus status;
    private TaskPriority priority;
    private LocalDateTime dueDate;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
}
