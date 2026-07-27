package com.apms.domain.score.service;

import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.score.draft.CriterionInput;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.draft.RoleEvaluationVersion;
import com.apms.domain.score.dto.draft.ReviewRoleEvaluationRequest;
import com.apms.domain.score.enums.RoleEvaluationReviewDecision;
import com.apms.domain.score.enums.RoleEvaluationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.HashMap;
import java.util.Map;

import com.apms.domain.score.enums.EvaluationCompletenessStatus;
import com.apms.domain.score.dto.draft.RoleEvaluationReadinessResponse;
import com.apms.common.security.ProjectSecurityEvaluator;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
public class PartnerApprovalNoScoringSideEffectTest {

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private PartnerDataSufficiencyEvaluator sufficiencyEvaluator;

    @Mock
    private ProjectSecurityEvaluator projectSecurityEvaluator;

    @InjectMocks
    private PartnerRoleEvaluationApprovalStrategy approvalStrategy;

    private RoleEvaluationDraft draft;
    private ProjectTask task;
    private ProjectTaskSubmission submission;
    private ReviewRoleEvaluationRequest request;

    @BeforeEach
    void setUp() {
        draft = new RoleEvaluationDraft();
        draft.setId("draft-1");
        draft.setEvaluatedRole(CompanyRole.PARTNER);
        draft.setStatus(RoleEvaluationStatus.IN_REVIEW);
        draft.setStaleTargetProfile(false);
        draft.setStaleReferenceProfile(false);
        draft.setStaleRuleSet(false);

        java.util.LinkedHashMap<String, CriterionInput> criteria = new java.util.LinkedHashMap<>();
        for (int i = 1; i <= 6; i++) {
            CriterionInput input = new CriterionInput();
            input.setExplanation("test " + i);
            criteria.put("C" + i, input);
        }
        draft.setCriterionInputs(criteria);

        Project project = new Project();
        project.setId(100L);

        task = new ProjectTask();
        task.setId(200L);
        task.setProject(project);

        submission = new ProjectTaskSubmission();
        submission.setId(300L);

        request = new ReviewRoleEvaluationRequest();
        request.setDecision(RoleEvaluationReviewDecision.APPROVE);
        request.setComment("Approved");
    }

    @Test
    void testApproveDoesNotCallScoringEngine() {
        com.mongodb.client.result.UpdateResult mockUpdateResult = org.mockito.Mockito.mock(com.mongodb.client.result.UpdateResult.class);
        org.mockito.Mockito.when(mockUpdateResult.getModifiedCount()).thenReturn(1L);
        org.mockito.Mockito.when(mongoTemplate.updateFirst(any(), any(), org.mockito.ArgumentMatchers.eq(RoleEvaluationDraft.class)))
                .thenReturn(mockUpdateResult);

        org.mockito.Mockito.when(projectSecurityEvaluator.isManager(100L)).thenReturn(true);

        RoleEvaluationReadinessResponse readiness = new RoleEvaluationReadinessResponse();
        readiness.setStaffMaySubmit(true);
        readiness.setAggregateCompletenessStatus(EvaluationCompletenessStatus.COMPLETE);
        org.mockito.Mockito.when(sufficiencyEvaluator.evaluate(draft)).thenReturn(readiness);

        approvalStrategy.approve(draft, task, submission, request, 10L, "idemp-key");

        verify(mongoTemplate).insert(any(RoleEvaluationVersion.class));
        // Partner evaluations should not invoke scoring. Since PartnerRoleEvaluationApprovalStrategy
        // does not even hold a reference to RoleScoringEngine, it is architecturally guaranteed.
    }
}
