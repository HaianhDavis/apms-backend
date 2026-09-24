package com.apms.domain.project.dto;

import com.apms.common.enums.TaskPriority;
import com.apms.common.enums.TaskStatus;
import com.apms.common.enums.TaskType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskHistoryDetailResponse {
    private Long taskId;
    private String taskCode;
    private String title;
    private String description;
    private String deliverable;
    private TaskType taskType;
    private TaskPriority priority;
    private TaskStatus status;
    private int revisionCount;
    private LocalDateTime claimedAt;
    private LocalDateTime lastSubmittedAt;
    private LocalDateTime completedAt;
    private List<TaskTimelineEventResponse> activities;
}
