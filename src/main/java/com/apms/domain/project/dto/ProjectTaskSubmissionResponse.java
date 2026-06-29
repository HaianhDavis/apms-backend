package com.apms.domain.project.dto;

import com.apms.common.enums.SubmissionStatus;
import com.apms.common.enums.SubmissionType;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ProjectTaskSubmissionResponse {
    private Long id;
    private Long projectTaskId;
    private Long projectId;
    private Long submittedByUserId;
    private SubmissionType submissionType;
    private String targetEntityType;
    private String targetEntityId;
    private SubmissionStatus status;
    private String note;
    private LocalDateTime submittedAt;
    private Long reviewedByUserId;
    private LocalDateTime reviewedAt;
    private String reviewComment;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
