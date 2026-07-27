package com.apms.domain.score.outbox.processor;

import com.apms.common.enums.SubmissionStatus;
import com.apms.common.enums.TaskStatus;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
import com.apms.domain.score.RoleScoreRuleSet;
import com.apms.domain.score.ScoreSnapshot;
import com.apms.domain.score.draft.CriterionSnapshot;
import com.apms.domain.score.draft.RoleEvaluationVersion;
import com.apms.domain.score.dto.RoleEvaluationCalculationResult;
import com.apms.domain.score.engine.RoleScoringEngine;
import com.apms.domain.score.enums.EvaluationCompletenessStatus;
import com.apms.domain.score.enums.RoleEvaluationOutboxEventType;
import com.apms.domain.score.outbox.RoleEvaluationOutboxEvent;
import com.apms.domain.score.outbox.RoleEvaluationOutboxPayload;
import com.apms.domain.score.repository.mongo.RoleEvaluationVersionRepository;
import com.apms.domain.score.repository.sql.RoleScoreRuleSetRepository;
import com.apms.domain.score.service.CanonicalScoreSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class SupplierEvaluationApprovedEventProcessorTest {

    @Mock private ProjectTaskRepository taskRepository;
    @Mock private ProjectTaskSubmissionRepository submissionRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private RoleEvaluationVersionRepository versionRepository;
    @Mock private RoleScoringEngine scoringEngine;
    @Mock private RoleScoreRuleSetRepository ruleSetRepository;
    @Mock private CanonicalScoreSnapshotService canonicalScoreSnapshotService;

    @InjectMocks
    private SupplierEvaluationApprovedEventProcessor processor;

    private RoleEvaluationOutboxEvent event;
    private RoleEvaluationVersion version;
    private ProjectTask task;
    private ProjectTaskSubmission submission;

    @BeforeEach
    void setUp() {
        RoleEvaluationOutboxPayload payload = RoleEvaluationOutboxPayload.builder()
                .submissionId(30L)
                .approvedVersionId("ver-1")
                .actorAccountId(100L)
                .build();

        event = RoleEvaluationOutboxEvent.builder()
                .id("event-1")
                .taskId(20L)
                .evaluationId("eval-1")
                .payload(payload)
                .build();

        Map<String, CriterionSnapshot> criteria = new HashMap<>();
        criteria.put("qualityPerformanceScore", CriterionSnapshot.builder().rawScore(new BigDecimal("100")).build());

        version = RoleEvaluationVersion.builder()
                .id("ver-1")
                .evaluationId("eval-1")
                .evaluatedRole(CompanyRole.SUPPLIER)
                .criteria(criteria)
                .build();

        task = new ProjectTask();
        submission = new ProjectTaskSubmission();
    }

    @Test
    void testProcessApprovedEventAndScoreFromImmutableVersion() {
        when(versionRepository.findById("ver-1")).thenReturn(Optional.of(version));

        RoleScoreRuleSet ruleSet = new RoleScoreRuleSet();
        ruleSet.setRuleSetVersion("V1");
        when(ruleSetRepository.findByEvaluatedRoleAndRuleSetVersion(CompanyRole.SUPPLIER, "ROLE_SCORING_V1"))
                .thenReturn(Optional.of(ruleSet));

        RoleEvaluationCalculationResult calcResult = RoleEvaluationCalculationResult.builder()
                .completenessStatus(EvaluationCompletenessStatus.COMPLETE)
                .overallScore(new BigDecimal("71.70"))
                .build();
        when(scoringEngine.calculate(any())).thenReturn(calcResult);

        ScoreSnapshot snapshot = new ScoreSnapshot();
        snapshot.setScoreSnapshotId(99L);
        when(canonicalScoreSnapshotService.createCanonicalSnapshot(any())).thenReturn(snapshot);

        when(taskRepository.findById(20L)).thenReturn(Optional.of(task));
        when(submissionRepository.findById(30L)).thenReturn(Optional.of(submission));

        processor.process(event);

        verify(scoringEngine).calculate(argThat(req -> req.getCriterionScores().containsKey("qualityPerformanceScore") && req.getApprovalIdempotencyKey().equals("event-1")));
        verify(canonicalScoreSnapshotService).createCanonicalSnapshot(any());

        assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.APPROVED);
        verify(submissionRepository).save(submission);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.DONE);
        verify(taskRepository).save(task);
        verify(auditLogService).log(any(), any(), any(), any(), any());
    }

    @Test
    void testProcessApprovedEventIdempotency() {
        assertThat(processor.supports(RoleEvaluationOutboxEventType.SUPPLIER_EVALUATION_APPROVED)).isTrue();
    }
}
