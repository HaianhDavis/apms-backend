package com.apms.domain.score.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.SubmissionStatus;
import com.apms.common.enums.TaskStatus;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
import com.apms.domain.score.RoleScoreRuleSet;
import com.apms.domain.score.ScoreSnapshot;
import com.apms.domain.score.draft.CriterionInput;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.dto.RoleEvaluationCalculationRequest;
import com.apms.domain.score.dto.RoleEvaluationCalculationResult;
import com.apms.domain.score.dto.draft.ReviewRoleEvaluationRequest;
import com.apms.domain.score.engine.RoleScoringEngine;
import com.apms.domain.score.enums.EvaluationCompletenessStatus;
import com.apms.domain.score.enums.RoleEvaluationStatus;
import com.apms.domain.score.repository.mongo.RoleEvaluationDraftRepository;
import com.apms.domain.score.repository.sql.RoleScoreRuleSetRepository;
import com.apms.domain.score.repository.sql.ScoreSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class CompetitorApprovalRegressionTest {

    @Mock
    private RoleEvaluationDraftRepository draftRepository;

    @Mock
    private ProjectTaskRepository taskRepository;

    @Mock
    private ProjectTaskSubmissionRepository submissionRepository;

    @Mock
    private ScoreSnapshotRepository scoreSnapshotRepository;

    @Mock
    private CanonicalScoreSnapshotService canonicalScoreSnapshotService;

    @Mock
    private RoleScoringEngine scoringEngine;

    @Mock
    private RoleScoreRuleSetRepository ruleSetRepository;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private CompetitorRoleEvaluationApprovalStrategy strategy;

    private RoleEvaluationDraft draft;
    private ProjectTask task;
    private ProjectTaskSubmission submission;
    private ReviewRoleEvaluationRequest request;

    @BeforeEach
    void setUp() {
        draft = new RoleEvaluationDraft();
        draft.setId("draft-1");
        draft.setEvaluatedRole(CompanyRole.COMPETITOR);
        draft.setStatus(RoleEvaluationStatus.IN_REVIEW);
        draft.setRuleSetVersion("1");

        java.util.LinkedHashMap<String, CriterionInput> criteria = new java.util.LinkedHashMap<>();
        for (int i = 1; i <= 6; i++) {
            CriterionInput input = new CriterionInput();
            input.setRawScore(new BigDecimal("50"));
            input.setManagerConfirmed(true);
            criteria.put("C" + i, input);
        }
        draft.setCriterionInputs(criteria);

        Project project = new Project();
        project.setId(100L);

        task = new ProjectTask();
        task.setId(200L);
        task.setProject(project);
        task.setStatus(TaskStatus.IN_REVIEW);

        submission = new ProjectTaskSubmission();
        submission.setId(300L);
        submission.setStatus(SubmissionStatus.IN_REVIEW);

        request = new ReviewRoleEvaluationRequest();
    }

    @Test
    void testApprove_Success() {
        when(scoreSnapshotRepository.findBySourceEvaluationDraftId("draft-1")).thenReturn(Optional.empty());

        RoleScoreRuleSet ruleSet = new RoleScoreRuleSet();
        ruleSet.setRuleSetVersion("1");
        when(ruleSetRepository.findByEvaluatedRoleAndRuleSetVersion(CompanyRole.COMPETITOR, "1")).thenReturn(Optional.of(ruleSet));

        RoleEvaluationCalculationResult calcResult = mock(RoleEvaluationCalculationResult.class);
        when(calcResult.getCompletenessStatus()).thenReturn(EvaluationCompletenessStatus.COMPLETE);
        when(scoringEngine.calculate(any(RoleEvaluationCalculationRequest.class))).thenReturn(calcResult);

        ScoreSnapshot snapshot = new ScoreSnapshot();
        snapshot.setScoreSnapshotId(999L);
        when(canonicalScoreSnapshotService.createCanonicalSnapshot(any(RoleEvaluationCalculationRequest.class))).thenReturn(snapshot);

        strategy.approve(draft, task, submission, request, 10L, "idempotency-key");

        verify(scoringEngine).calculate(any(RoleEvaluationCalculationRequest.class));
        verify(canonicalScoreSnapshotService).createCanonicalSnapshot(any(RoleEvaluationCalculationRequest.class));

        assertEquals(RoleEvaluationStatus.APPROVED, draft.getStatus());
        assertEquals(999L, draft.getApprovedSnapshotId());
        assertFalse(draft.getActive());

        assertEquals(SubmissionStatus.APPROVED, submission.getStatus());
        assertEquals(TaskStatus.DONE, task.getStatus());

        verify(draftRepository, times(2)).save(draft); // Once for processing, once for approved
        verify(submissionRepository).save(submission);
        verify(taskRepository).save(task);
    }

    @Test
    void testApprove_FailsIfIncomplete() {
        when(scoreSnapshotRepository.findBySourceEvaluationDraftId("draft-1")).thenReturn(Optional.empty());

        RoleScoreRuleSet ruleSet = new RoleScoreRuleSet();
        ruleSet.setRuleSetVersion("1");
        when(ruleSetRepository.findByEvaluatedRoleAndRuleSetVersion(CompanyRole.COMPETITOR, "1")).thenReturn(Optional.of(ruleSet));

        RoleEvaluationCalculationResult calcResult = mock(RoleEvaluationCalculationResult.class);
        when(calcResult.getCompletenessStatus()).thenReturn(EvaluationCompletenessStatus.INCOMPLETE);
        when(scoringEngine.calculate(any(RoleEvaluationCalculationRequest.class))).thenReturn(calcResult);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            strategy.approve(draft, task, submission, request, 10L, "idempotency-key");
        });

        assertTrue(ex.getMessage().contains("was not COMPLETE"));
        assertEquals(RoleEvaluationStatus.APPROVAL_FAILED, draft.getStatus());
    }
}
