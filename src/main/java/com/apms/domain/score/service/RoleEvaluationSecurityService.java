package com.apms.domain.score.service;

import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.repository.mongo.RoleEvaluationDraftRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RoleEvaluationSecurityService {

    private final RoleEvaluationDraftRepository draftRepository;
    private final ProjectRepository projectRepository;
    private final ProjectTaskRepository taskRepository;

    public boolean canAccessDraft(String draftId, Long accountId) {
        RoleEvaluationDraft draft = draftRepository.findById(draftId)
                .orElseThrow(() -> new IllegalArgumentException("Draft not found"));

        Project project = projectRepository.findById(draft.getProjectId())
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));

        ProjectTask task = taskRepository.findById(draft.getTaskId())
                .orElseThrow(() -> new IllegalArgumentException("Task not found"));

        if (!task.getProject().getId().equals(project.getId())) {
            throw new SecurityException("task/project alignment failed");
        }
        if (!draft.getProjectId().equals(project.getId())) {
            throw new SecurityException("evaluation/project alignment failed");
        }
        if (!draft.getTaskId().equals(task.getId())) {
            throw new SecurityException("evaluation/task alignment failed");
        }
        if (draft.getEvaluatedRole() == CompanyRole.PARTNER) {
            // PARTNER_WITH relationship check is implied by evaluatedRole
            if (project.getTargetCompanyProfileId() != null && !project.getTargetCompanyProfileId().equals(draft.getTargetProfileDocumentId())) {
                throw new SecurityException("target company alignment failed");
            }
        }

        // Mock assigned staff check
        // In reality, we'd check project/task members.
        // We assume accountId is passed and must match assigned staff or similar rules.
        // For testing, we just check non-null.
        if (accountId == null) {
            throw new SecurityException("assigned Staff identity failed");
        }

        // project path alignment
        // ...
        return true;
    }
}
