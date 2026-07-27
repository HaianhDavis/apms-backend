package com.apms.domain.score.service;

import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.dto.draft.SubmitRoleEvaluationRequest;
import com.apms.domain.score.repository.mongo.RoleEvaluationDraftRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
import com.apms.common.enums.SubmissionType;
import com.apms.common.enums.SubmissionStatus;
import com.apms.domain.score.enums.RoleEvaluationStatus;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class RoleEvaluationSubmissionServiceTest {

    @Mock
    private RoleEvaluationDraftRepository draftRepository;

    @Mock
    private ProjectTaskRepository taskRepository;

    @Mock
    private ProjectTaskSubmissionRepository submissionRepository;

    @Mock
    private RoleEvaluationSubmissionStrategy strategy;

    private RoleEvaluationSubmissionService service;

    private RoleEvaluationDraft draft;
    private ProjectTask task;
    private ProjectTaskSubmission submission;
    private SubmitRoleEvaluationRequest request;

    @BeforeEach
    void setUp() {
        service = new RoleEvaluationSubmissionService(draftRepository, taskRepository, submissionRepository, List.of(strategy));

        draft = new RoleEvaluationDraft();
        draft.setId("draft-1");
        draft.setTaskId(100L);
        draft.setProjectId(10L);
        draft.setStatus(RoleEvaluationStatus.DRAFT);
        draft.setEvaluatedRole(CompanyRole.PARTNER);

        task = new ProjectTask();
        task.setId(100L);
        com.apms.domain.project.Project project = new com.apms.domain.project.Project();
        project.setId(10L);
        task.setProject(project);

        submission = new ProjectTaskSubmission();
        submission.setId(200L);
        submission.setSubmissionType(SubmissionType.ROLE_EVALUATION);
        submission.setStatus(SubmissionStatus.DRAFT);
        submission.setTargetEntityId("draft-1");

        request = new SubmitRoleEvaluationRequest();
    }

    @Test
    void submitDraft_DelegatesToStrategy() {
        when(draftRepository.findById("draft-1")).thenReturn(Optional.of(draft));
        when(taskRepository.findById(100L)).thenReturn(Optional.of(task));
        when(submissionRepository.findByProjectTask_Id(100L)).thenReturn(List.of(submission));
        when(strategy.supports(CompanyRole.PARTNER)).thenReturn(true);

        service.submitDraft("draft-1", request, 1L);

        verify(strategy).submit(draft, task, submission, request, 1L);
    }

    @Test
    void submitDraft_ThrowsExceptionWhenStrategyNotFound() {
        when(draftRepository.findById("draft-1")).thenReturn(Optional.of(draft));
        when(taskRepository.findById(100L)).thenReturn(Optional.of(task));
        when(strategy.supports(CompanyRole.PARTNER)).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> service.submitDraft("draft-1", request, 1L));
    }
}
