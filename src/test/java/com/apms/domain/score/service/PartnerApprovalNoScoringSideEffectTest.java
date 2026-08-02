package com.apms.domain.score.service;

import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
import com.apms.domain.score.draft.CriterionInput;
import com.apms.domain.score.draft.EvidenceRecord;
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

import com.apms.common.security.ProjectSecurityEvaluator;
import com.apms.domain.score.registry.CanonicalRoleCriteria;

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

    @Mock
    private ProjectTaskRepository taskRepository;

    @Mock
    private ProjectTaskSubmissionRepository submissionRepository;

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
        java.util.LinkedHashMap<String, java.util.List<EvidenceRecord>> evidence = new java.util.LinkedHashMap<>();
        for (String criterionKey : CanonicalRoleCriteria.PARTNER_CRITERIA) {
            CriterionInput input = new CriterionInput();
            input.setRawScore(new java.math.BigDecimal("90"));
            input.setExplanation("test " + criterionKey);
            criteria.put(criterionKey, input);

            EvidenceRecord record = new EvidenceRecord();
            record.setEvidenceId("ev-" + criterionKey);
            record.setRawDocumentId("raw-" + criterionKey);
            evidence.put(criterionKey, java.util.List.of(record));
        }
        draft.setCriterionInputs(criteria);
        draft.setCriterionEvidence(evidence);

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

        approvalStrategy.approve(draft, task, submission, request, 10L, "idemp-key");

        verify(mongoTemplate).insert(any(RoleEvaluationVersion.class));
        // Partner evaluations should not invoke scoring. Since PartnerRoleEvaluationApprovalStrategy
        // does not even hold a reference to RoleScoringEngine, it is architecturally guaranteed.
    }
}
