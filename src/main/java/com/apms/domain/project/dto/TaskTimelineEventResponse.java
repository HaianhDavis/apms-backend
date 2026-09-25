package com.apms.domain.project.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskTimelineEventResponse {
    private String type; // TASK_CLAIMED, SUBMITTED, REVISION_REQUESTED, RESUBMITTED, APPROVED, COMPLETED
    private Integer submissionNumber;
    private String title;
    private String detail;
    private String note;
    private Long actorId;
    private String actorName;
    private LocalDateTime occurredAt;
}
