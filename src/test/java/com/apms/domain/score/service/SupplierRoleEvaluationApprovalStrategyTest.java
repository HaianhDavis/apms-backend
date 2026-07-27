package com.apms.domain.score.service;

import com.apms.common.security.ProjectSecurityEvaluator;
import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.score.draft.CriterionInput;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.dto.draft.CriterionReadinessResult;
import com.apms.domain.score.dto.draft.ReviewRoleEvaluationRequest;
import com.apms.domain.score.dto.draft.RoleEvaluationReadinessResponse;
import com.apms.domain.score.enums.EvaluationCompletenessStatus;
import com.apms.domain.score.enums.RoleEvaluationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupplierRoleEvaluationApprovalStrategyTest {

    @Mock
    private MongoTemplate mongoTemplate;
    @Mock
    private SupplierDataSufficiencyEvaluator sufficiencyEvaluator;
    @Mock
    private ProjectSecurityEvaluator projectSecurityEvaluator;

    @InjectMocks
    private SupplierRoleEvaluationApprovalStrategy strategy;

    private RoleEvaluationDraft draft;
    private ProjectTask task;
    private ProjectTaskSubmission submission;
    private ReviewRoleEvaluationRequest request;

    @BeforeEach
    void setUp() {
        draft = new RoleEvaluationDraft();
        draft.setId("draft-1");
        draft.setStatus(RoleEvaluationStatus.IN_REVIEW);
        draft.setOptimisticVersion(1L);
        draft.setCriterionInputs(new java.util.LinkedHashMap<>());
        draft.setWorkingRevisionNumber(2);
        draft.setSubmittedRevisionNumber(2);

        Project project = new Project();
        project.setId(1L);

        task = new ProjectTask();
        task.setId(200L);
        task.setProject(project);

        submission = new ProjectTaskSubmission();
        submission.setId(300L);

        request = new ReviewRoleEvaluationRequest();
        request.setComment("Looks good");
    }

    @Test
    void supportsOnlySupplier() {
        assertThat(strategy.supports(CompanyRole.SUPPLIER)).isTrue();
        assertThat(strategy.supports(CompanyRole.PARTNER)).isFalse();
    }

    @Test
    void approveFailsIfNotManager() {
        when(projectSecurityEvaluator.isManager(1L)).thenReturn(false);

        assertThatThrownBy(() -> strategy.approve(draft, task, submission, request, 999L, "idempotency-1"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void approveFailsIfIncomplete() {
        when(projectSecurityEvaluator.isManager(1L)).thenReturn(true);

        RoleEvaluationReadinessResponse readiness = RoleEvaluationReadinessResponse.builder()
                .aggregateCompletenessStatus(EvaluationCompletenessStatus.INCOMPLETE)
                .build();
        when(sufficiencyEvaluator.evaluate(draft)).thenReturn(readiness);

        assertThatThrownBy(() -> strategy.approve(draft, task, submission, request, 999L, "idempotency-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot approve INCOMPLETE evaluation");
    }

    @Test
    void approveFailsIfPartialWithoutComment() {
        when(projectSecurityEvaluator.isManager(1L)).thenReturn(true);

        RoleEvaluationReadinessResponse readiness = RoleEvaluationReadinessResponse.builder()
                .aggregateCompletenessStatus(EvaluationCompletenessStatus.PARTIAL)
                .build();
        when(sufficiencyEvaluator.evaluate(draft)).thenReturn(readiness);

        request.setComment("");

        assertThatThrownBy(() -> strategy.approve(draft, task, submission, request, 999L, "idempotency-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Manager justification is required");
    }

    @Test
    void approveSucceedsIfComplete() {
        when(projectSecurityEvaluator.isManager(1L)).thenReturn(true);

        RoleEvaluationReadinessResponse readiness = RoleEvaluationReadinessResponse.builder()
                .aggregateCompletenessStatus(EvaluationCompletenessStatus.COMPLETE)
                .build();
        when(sufficiencyEvaluator.evaluate(draft)).thenReturn(readiness);

        com.mongodb.client.result.UpdateResult mockResult = mock(com.mongodb.client.result.UpdateResult.class);
        when(mockResult.getModifiedCount()).thenReturn(1L);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(RoleEvaluationDraft.class))).thenReturn(mockResult);

        strategy.approve(draft, task, submission, request, 999L, "idempotency-1");

        assertThat(draft.getStatus()).isEqualTo(RoleEvaluationStatus.APPROVAL_PROCESSING);
        verify(mongoTemplate).insert(any(com.apms.domain.score.draft.RoleEvaluationVersion.class));
        verify(mongoTemplate).insert(any(com.apms.domain.score.outbox.RoleEvaluationOutboxEvent.class));
    }

    @Test
    void rejectThrowsUnsupported() {
        assertThatThrownBy(() -> strategy.reject(draft, task, submission, request, 999L))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
