package com.apms.domain.score.service;

import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.dto.draft.SubmitRoleEvaluationRequest;

public interface RoleEvaluationSubmissionStrategy {
    boolean supports(CompanyRole role);

    void submit(RoleEvaluationDraft draft, ProjectTask task, ProjectTaskSubmission existingSubmission, SubmitRoleEvaluationRequest request, Long accountId);
}
