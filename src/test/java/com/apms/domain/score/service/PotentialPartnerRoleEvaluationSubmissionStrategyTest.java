package com.apms.domain.score.service;

import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.dto.draft.RoleEvaluationReadinessResponse;
import com.apms.domain.score.dto.draft.SubmitRoleEvaluationRequest;
import com.apms.domain.score.enums.EvaluationCompletenessStatus;
import com.apms.domain.score.enums.RoleEvaluationOutboxEventType;
import com.apms.domain.score.enums.RoleEvaluationStatus;
import com.apms.domain.score.outbox.RoleEvaluationOutboxEvent;
import com.apms.domain.user.Account;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class PotentialPartnerRoleEvaluationSubmissionStrategyTest {

    @Mock private MongoTemplate mongoTemplate;
    @Mock private PotentialPartnerDataSufficiencyEvaluator sufficiencyEvaluator;

    @InjectMocks
    private PotentialPartnerRoleEvaluationSubmissionStrategy strategy;

    private RoleEvaluationDraft draft;
    private ProjectTask task;
    private ProjectTaskSubmission submission;
    private SubmitRoleEvaluationRequest request;
    private Account account;

    @BeforeEach
    void setUp() {
        account = new Account();
        account.setId(100L);

        Project project = new Project();
        project.setId(10L);

        task = new ProjectTask();
        task.setId(20L);
        task.setProject(project);
        task.setAssignedToAccount(account);

        submission = new ProjectTaskSubmission();
        
        draft = new RoleEvaluationDraft();
        draft.setId("draft-1");
        draft.setEvaluatedRole(CompanyRole.POTENTIAL_PARTNER);
        draft.setStatus(RoleEvaluationStatus.DRAFT);
        draft.setOptimisticVersion(1L);

        request = new SubmitRoleEvaluationRequest();
    }

    @Test
    void testSubmitCreatesOutboxEvent() {
        when(sufficiencyEvaluator.evaluate(draft)).thenReturn(RoleEvaluationReadinessResponse.builder()
                .staffMaySubmit(true)
                .aggregateCompletenessStatus(EvaluationCompletenessStatus.COMPLETE).build());

        when(mongoTemplate.updateFirst(any(), any(), eq(RoleEvaluationDraft.class)))
                .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null));

        strategy.submit(draft, task, submission, request, 100L);

        // Verify Outbox Event Creation
        ArgumentCaptor<RoleEvaluationOutboxEvent> eventCaptor = ArgumentCaptor.forClass(RoleEvaluationOutboxEvent.class);
        verify(mongoTemplate).insert(eventCaptor.capture());
        RoleEvaluationOutboxEvent event = eventCaptor.getValue();
        assertThat(event.getEventType()).isEqualTo(RoleEvaluationOutboxEventType.POTENTIAL_PARTNER_EVALUATION_SUBMITTED);
        assertThat(event.getEvaluationId()).isEqualTo("draft-1");

        // Verify Draft state mutated to IN_REVIEW
        assertThat(draft.getStatus()).isEqualTo(RoleEvaluationStatus.IN_REVIEW);
    }
}
