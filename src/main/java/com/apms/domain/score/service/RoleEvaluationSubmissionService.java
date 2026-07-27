package com.apms.domain.score.service;

import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.dto.draft.SubmitRoleEvaluationRequest;
import com.apms.domain.score.repository.mongo.RoleEvaluationDraftRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoleEvaluationSubmissionService {

    private final RoleEvaluationDraftRepository draftRepository;
    private final ProjectTaskRepository taskRepository;
    private final ProjectTaskSubmissionRepository submissionRepository;
    private final List<RoleEvaluationSubmissionStrategy> strategies;

    public void submitDraft(String draftId, SubmitRoleEvaluationRequest request, Long accountId) {
        RoleEvaluationDraft draft = draftRepository.findById(draftId)
                .orElseThrow(() -> new IllegalArgumentException("Draft not found: " + draftId));

        ProjectTask task = taskRepository.findById(draft.getTaskId())
                .orElseThrow(() -> new IllegalStateException("Task not found"));

        if (!task.getProject().getId().equals(draft.getProjectId())) {
            throw new IllegalStateException("Draft project ID mismatch");
        }

        // Use persisted targetRelationshipType / evaluatedRole for strategy selection, not request input
        RoleEvaluationSubmissionStrategy strategy = strategies.stream()
                .filter(s -> s.supports(draft.getEvaluatedRole()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No submission strategy for role: " + draft.getEvaluatedRole()));

        Optional<ProjectTaskSubmission> existingSubmission = submissionRepository.findByProjectTask_Id(draft.getTaskId()).stream()
                .filter(s -> s.getTargetEntityId().equals(draftId))
                .findFirst();

        strategy.submit(draft, task, existingSubmission.orElse(null), request, accountId);
    }
}
