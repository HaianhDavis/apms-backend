package com.apms.domain.score.service;

import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.dto.draft.ReviewRoleEvaluationRequest;

public interface RoleEvaluationApprovalStrategy {
    boolean supports(CompanyRole role);

    void approve(RoleEvaluationDraft draft, ProjectTask task, ProjectTaskSubmission submission, ReviewRoleEvaluationRequest request, Long accountId, String idempotencyKey);

    void requestRevision(RoleEvaluationDraft draft, ProjectTask task, ProjectTaskSubmission submission, ReviewRoleEvaluationRequest request, Long accountId);

    void reject(RoleEvaluationDraft draft, ProjectTask task, ProjectTaskSubmission submission, ReviewRoleEvaluationRequest request, Long accountId);
}
