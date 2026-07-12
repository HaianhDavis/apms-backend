package com.apms.domain.score.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.SubmissionStatus;
import com.apms.common.enums.SubmissionType;
import com.apms.common.enums.TaskStatus;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
import com.apms.domain.score.draft.CriterionInput;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.dto.draft.SubmitRoleEvaluationRequest;
import com.apms.domain.score.enums.CriterionInputMethod;
import com.apms.domain.score.enums.RoleEvaluationStatus;
import com.apms.domain.score.repository.mongo.RoleEvaluationDraftRepository;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoleEvaluationSubmissionServiceTest {

    @Mock private RoleEvaluationDraftRepository draftRepository;
    @Mock private ProjectTaskRepository taskRepository;
    @Mock private ProjectTaskSubmissionRepository submissionRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private AuditLogService auditLogService;

    @InjectMocks
    private RoleEvaluationSubmissionService service;

    private RoleEvaluationDraft draft;
    private ProjectTask task;
    private Account account;

    @BeforeEach
    void setUp() {
        draft = new RoleEvaluationDraft();
        draft.setId("draft-1");
        draft.setTaskId(10L);
        draft.setStatus(RoleEvaluationStatus.DRAFT);

        Project project = new Project();
        project.setId(1L);

        task = new ProjectTask();
        task.setId(10L);
        task.setProject(project);

        account = new Account();
        account.setId(999L);
    }

    @Test
    void submitDraft_Success() {
        CriterionInput input = new CriterionInput();
        input.setRawScore(new BigDecimal("80.0"));
        input.setExplanation("Valid");
        input.setEvidenceIds(List.of("ev-1"));
        input.setInputMethod(CriterionInputMethod.MANUAL_REVIEWED);
        draft.getCriterionInputs().put("someCriterion", input);

        when(draftRepository.findById("draft-1")).thenReturn(Optional.of(draft));
        when(submissionRepository.findByProjectTask_Id(10L)).thenReturn(List.of());
        when(taskRepository.findById(10L)).thenReturn(Optional.of(task));
        when(accountRepository.findById(999L)).thenReturn(Optional.of(account));

        service.submitDraft("draft-1", new SubmitRoleEvaluationRequest(), 999L);

        // Verify draft
        assertEquals(RoleEvaluationStatus.IN_REVIEW, draft.getStatus());
        assertEquals(999L, draft.getSubmittedByAccountId());
        verify(draftRepository).save(draft);

        // Verify task
        assertEquals(TaskStatus.IN_REVIEW, task.getStatus());
        verify(taskRepository).save(task);

        // Verify submission
        ArgumentCaptor<ProjectTaskSubmission> subCaptor = ArgumentCaptor.forClass(ProjectTaskSubmission.class);
        verify(submissionRepository).save(subCaptor.capture());
        ProjectTaskSubmission submission = subCaptor.getValue();
        assertEquals(SubmissionType.ROLE_EVALUATION, submission.getSubmissionType());
        assertEquals("ROLE_EVALUATION_DRAFT", submission.getTargetEntityType());
        assertEquals("draft-1", submission.getTargetEntityId());
        assertEquals(SubmissionStatus.IN_REVIEW, submission.getStatus());

        // Verify audit
        verify(auditLogService).log(eq(999L), eq(AuditAction.ROLE_EVALUATION_SUBMITTED), any(), eq("draft-1"), any());
    }

    @Test
    void submitDraft_MissingEvidenceForManual_ThrowsException() {
        CriterionInput input = new CriterionInput();
        input.setRawScore(new BigDecimal("80.0"));
        input.setExplanation("No evidence");
        input.setInputMethod(CriterionInputMethod.MANUAL_REVIEWED);
        draft.getCriterionInputs().put("someCriterion", input);

        when(draftRepository.findById("draft-1")).thenReturn(Optional.of(draft));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            service.submitDraft("draft-1", new SubmitRoleEvaluationRequest(), 999L));
            
        assertTrue(ex.getMessage().contains("must have evidence"));
    }

    @Test
    void submitDraft_DuplicateActiveSubmission_ThrowsException() {
        CriterionInput input = new CriterionInput();
        input.setRawScore(new BigDecimal("80.0"));
        input.setExplanation("Valid");
        input.setEvidenceIds(List.of("ev-1"));
        draft.getCriterionInputs().put("someCriterion", input);

        ProjectTaskSubmission existing = new ProjectTaskSubmission();
        existing.setSubmissionType(SubmissionType.ROLE_EVALUATION);
        existing.setStatus(SubmissionStatus.IN_REVIEW);

        when(draftRepository.findById("draft-1")).thenReturn(Optional.of(draft));
        when(submissionRepository.findByProjectTask_Id(10L)).thenReturn(List.of(existing));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            service.submitDraft("draft-1", new SubmitRoleEvaluationRequest(), 999L));
            
        assertTrue(ex.getMessage().contains("active submission already exists"));
    }
}
