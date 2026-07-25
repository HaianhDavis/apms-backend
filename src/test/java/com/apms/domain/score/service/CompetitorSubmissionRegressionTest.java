package com.apms.domain.score.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.SubmissionStatus;
import com.apms.common.enums.SubmissionType;
import com.apms.common.enums.TaskStatus;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.company.enums.CompanyRole;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class CompetitorSubmissionRegressionTest {

    @Mock
    private RoleEvaluationDraftRepository draftRepository;

    @Mock
    private ProjectTaskRepository taskRepository;

    @Mock
    private ProjectTaskSubmissionRepository submissionRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private CompetitorRoleEvaluationSubmissionStrategy strategy;

    private RoleEvaluationDraft draft;
    private ProjectTask task;
    private SubmitRoleEvaluationRequest request;
    private Account account;

    @BeforeEach
    void setUp() {
        draft = new RoleEvaluationDraft();
        draft.setId("draft-1");
        draft.setTaskId(200L);
        draft.setStatus(RoleEvaluationStatus.DRAFT);
        draft.setEvaluatedRole(CompanyRole.COMPETITOR);

        java.util.LinkedHashMap<String, CriterionInput> criteria = new java.util.LinkedHashMap<>();
        CriterionInput input1 = new CriterionInput();
        input1.setRawScore(new BigDecimal("50"));
        input1.setExplanation("good");
        input1.setInputMethod(CriterionInputMethod.MANUAL_REVIEWED);
        input1.setEvidenceIds(List.of("doc-1"));
        criteria.put("C1", input1);

        draft.setCriterionInputs(criteria);

        Project project = new Project();
        project.setId(100L);

        task = new ProjectTask();
        task.setId(200L);
        task.setProject(project);

        account = new Account();
        account.setId(10L);

        request = new SubmitRoleEvaluationRequest();
    }

    @Test
    void testSubmit_Success() {
        when(accountRepository.findById(10L)).thenReturn(Optional.of(account));

        strategy.submit(draft, task, null, request, 10L);

        ArgumentCaptor<ProjectTaskSubmission> submissionCaptor = ArgumentCaptor.forClass(ProjectTaskSubmission.class);
        verify(submissionRepository).save(submissionCaptor.capture());
        ProjectTaskSubmission savedSubmission = submissionCaptor.getValue();
        assertEquals(SubmissionType.ROLE_EVALUATION, savedSubmission.getSubmissionType());
        assertEquals(SubmissionStatus.IN_REVIEW, savedSubmission.getStatus());

        assertEquals(TaskStatus.IN_REVIEW, task.getStatus());
        verify(taskRepository).save(task);

        assertEquals(RoleEvaluationStatus.IN_REVIEW, draft.getStatus());
        verify(draftRepository).save(draft);

        verify(auditLogService).log(eq(10L), eq(AuditAction.ROLE_EVALUATION_SUBMITTED), eq("ROLE_EVALUATION_DRAFT"), eq("draft-1"), anyString());
    }

    @Test
    void testSubmit_FailsIfScoreMissing() {
        draft.getCriterionInputs().get("C1").setRawScore(null);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> strategy.submit(draft, task, null, request, 10L));
        assertTrue(ex.getMessage().contains("has no score"));
    }

    @Test
    void testSubmit_FailsIfScoreInvalid() {
        draft.getCriterionInputs().get("C1").setRawScore(new BigDecimal("105"));
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> strategy.submit(draft, task, null, request, 10L));
        assertTrue(ex.getMessage().contains("score must be 0-100"));
    }

    @Test
    void testSubmit_FailsIfExplanationMissing() {
        draft.getCriterionInputs().get("C1").setExplanation("");
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> strategy.submit(draft, task, null, request, 10L));
        assertTrue(ex.getMessage().contains("must have an explanation"));
    }

    @Test
    void testSubmit_FailsIfManualButNoEvidence() {
        draft.getCriterionInputs().get("C1").setInputMethod(CriterionInputMethod.MANUAL_REVIEWED);
        draft.getCriterionInputs().get("C1").setEvidenceIds(null);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> strategy.submit(draft, task, null, request, 10L));
        assertTrue(ex.getMessage().contains("must have evidence"));
    }
}
