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
                .orElse(null);
        if (draft == null) return false;

        Project project = projectRepository.findById(draft.getProjectId())
                .orElse(null);
        if (project == null) return false;

        ProjectTask task = taskRepository.findById(draft.getTaskId())
                .orElse(null);
        if (task == null) return false;

        if (!task.getProject().getId().equals(project.getId())) {
            return false;
        }
        if (!draft.getProjectId().equals(project.getId())) {
            return false;
        }
        if (!draft.getTaskId().equals(task.getId())) {
            return false;
        }
        if (draft.getEvaluatedRole() == CompanyRole.PARTNER) {
            if (project.getTargetCompanyProfileId() != null && !project.getTargetCompanyProfileId().equals(draft.getTargetProfileDocumentId())) {
                return false;
            }
        }
        if (accountId == null) {
            return false;
        }
        return true;
    }
}
