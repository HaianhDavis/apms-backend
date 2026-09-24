package com.apms.domain.dashboard.dto;

import com.apms.common.enums.SubmissionStatus;
import com.apms.common.enums.SubmissionType;
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
public class ManagerReviewHistoryItemResponse {
    private Long submissionId;
    private Long projectId;
    private String projectName;
    private String targetCompanyName;
    private Long taskId;
    private String taskTitle;
    private TaskType taskType;
    private SubmissionType submissionType;
    private Integer submittedRevisionNumber;
    private Long submittedByUserId;
    private String submittedByName;
    private LocalDateTime submittedAt;
    private SubmissionStatus status;
    private Long reviewedByUserId;
    private String reviewedByName;
    private LocalDateTime reviewedAt;
    private String reviewComment;
    private String targetEntityType;
    private String targetEntityId;
    private String targetEntityName;
    private String note;
}
