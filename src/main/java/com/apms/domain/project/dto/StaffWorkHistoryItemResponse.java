package com.apms.domain.project.dto;

import com.apms.common.enums.TaskPriority;
import com.apms.common.enums.TaskStatus;
import com.apms.common.enums.TaskType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StaffWorkHistoryItemResponse {
    private Long taskId;
    private String taskCode;
    private String title;
    private String description;
    private String deliverable;
    private TaskType taskType;
    private TaskPriority priority;
    private TaskStatus status;
    private String latestReviewStatus;
    private LocalDateTime claimedAt;
    private LocalDateTime lastSubmittedAt;
    private LocalDateTime completedAt;
    private int revisionCount;
    private LocalDateTime lastActivityAt;
}
