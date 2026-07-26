package com.apms.domain.score.service;

import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.score.draft.CriterionInput;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.draft.RoleEvaluationVersion;
import com.apms.domain.score.dto.draft.ReviewRoleEvaluationRequest;
import com.apms.domain.score.dto.draft.RoleEvaluationReadinessResponse;
import com.apms.domain.score.enums.EvaluationCompletenessStatus;
import com.apms.domain.score.enums.RoleEvaluationOutboxEventType;
import com.apms.domain.score.enums.RoleEvaluationStatus;
import com.apms.domain.score.outbox.RoleEvaluationOutboxEvent;
import com.apms.common.security.ProjectSecurityEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class PotentialPartnerRoleEvaluationApprovalStrategyTest {

    @Mock private MongoTemplate mongoTemplate;
    @Mock private PotentialPartnerDataSufficiencyEvaluator sufficiencyEvaluator;
    @Mock private ProjectSecurityEvaluator projectSecurityEvaluator;

    @InjectMocks
    private PotentialPartnerRoleEvaluationApprovalStrategy strategy;

    private RoleEvaluationDraft draft;
    private ProjectTask task;
    private ProjectTaskSubmission submission;
    private ReviewRoleEvaluationRequest request;

    @BeforeEach
    void setUp() {
        Project project = new Project();
        project.setId(10L);

        task = new ProjectTask();
        task.setId(20L);
        task.setProject(project);

        submission = new ProjectTaskSubmission();
        submission.setId(30L);

        draft = new RoleEvaluationDraft();
        draft.setId("draft-1");
        draft.setEvaluatedRole(CompanyRole.POTENTIAL_PARTNER);
        draft.setStatus(RoleEvaluationStatus.IN_REVIEW);
        draft.setWorkingRevisionNumber(1);
        draft.setSubmittedRevisionNumber(1);
        draft.setSourceSnapshotHash("hash1");
        draft.setSubmittedSourceSnapshotHash("hash1");
        draft.setOptimisticVersion(1L);
        draft.setCriterionInputs(new LinkedHashMap<>());
        
        CriterionInput input = new CriterionInput();
        input.setRawScore(new BigDecimal("100"));
        draft.getCriterionInputs().put("strategicFitScore", input);

        request = new ReviewRoleEvaluationRequest();
        request.setComment("Looks good");
    }

    @Test
    void testApproveCreatesVersionAndOutboxEvent() {
        when(projectSecurityEvaluator.isManager(10L)).thenReturn(true);
        when(sufficiencyEvaluator.evaluate(draft)).thenReturn(RoleEvaluationReadinessResponse.builder()
                .aggregateCompletenessStatus(EvaluationCompletenessStatus.COMPLETE).build());

        when(mongoTemplate.updateFirst(any(), any(), eq(RoleEvaluationDraft.class)))
                .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null));

        strategy.approve(draft, task, submission, request, 100L, "idempotency-key");

        // Verify Version Creation
        ArgumentCaptor<RoleEvaluationVersion> versionCaptor = ArgumentCaptor.forClass(RoleEvaluationVersion.class);
        verify(mongoTemplate).insert(versionCaptor.capture());
        RoleEvaluationVersion savedVersion = versionCaptor.getValue();
        assertThat(savedVersion.getEvaluatedRole()).isEqualTo(CompanyRole.POTENTIAL_PARTNER);
        assertThat(savedVersion.getEvaluationId()).isEqualTo("draft-1");
        assertThat(savedVersion.getCriteria().get("strategicFitScore").getRawScore()).isEqualByComparingTo("100");

        // Verify Outbox Event Creation
        ArgumentCaptor<RoleEvaluationOutboxEvent> eventCaptor = ArgumentCaptor.forClass(RoleEvaluationOutboxEvent.class);
        verify(mongoTemplate).insert(eventCaptor.capture());
        RoleEvaluationOutboxEvent event = eventCaptor.getValue();
        assertThat(event.getEventType()).isEqualTo(RoleEvaluationOutboxEventType.POTENTIAL_PARTNER_EVALUATION_APPROVED);
        assertThat(event.getPayload().getApprovedVersionId()).isEqualTo(savedVersion.getId());

        // Verify Draft state mutated to APPROVAL_PROCESSING
        assertThat(draft.getStatus()).isEqualTo(RoleEvaluationStatus.APPROVAL_PROCESSING);
    }
}
