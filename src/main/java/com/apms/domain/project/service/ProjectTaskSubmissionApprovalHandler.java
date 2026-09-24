package com.apms.domain.project.service;

import com.apms.common.enums.SubmissionType;
import com.apms.domain.project.ProjectTaskSubmission;

public interface ProjectTaskSubmissionApprovalHandler {
    boolean supports(SubmissionType type);
    void handleApproval(ProjectTaskSubmission submission, Long reviewerId, String reviewComment);
    void handleRejection(ProjectTaskSubmission submission, Long reviewerId, String reviewComment);
}
