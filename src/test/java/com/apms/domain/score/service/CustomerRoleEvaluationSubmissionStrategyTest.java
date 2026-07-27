package com.apms.domain.score.service;

import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.dto.draft.SubmitRoleEvaluationRequest;
import com.apms.domain.score.enums.EvaluationCompletenessStatus;
import com.apms.domain.score.enums.RoleEvaluationStatus;
import com.apms.domain.user.Account;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerRoleEvaluationSubmissionStrategyTest {

    @Mock
    private MongoTemplate mongoTemplate;
    @Mock
    private CustomerDataSufficiencyEvaluator sufficiencyEvaluator;

    @InjectMocks
    private CustomerRoleEvaluationSubmissionStrategy strategy;

    private RoleEvaluationDraft draft;
    private ProjectTask task;
    private Account assignee;

    @BeforeEach
    void setUp() {
        draft = new RoleEvaluationDraft();
        draft.setId("draft-1");
        draft.setStatus(RoleEvaluationStatus.DRAFT);
        draft.setOptimisticVersion(1L);

        assignee = new Account();
        assignee.setId(100L);

        Project project = new Project();
        project.setId(1L);

        task = new ProjectTask();
        task.setId(200L);
        task.setProject(project);
        task.setAssignedToAccount(assignee);
    }

    @Test
    void supportsOnlyCustomer() {
        assertThat(strategy.supports(CompanyRole.CUSTOMER)).isTrue();
        assertThat(strategy.supports(CompanyRole.PARTNER)).isFalse();
        assertThat(strategy.supports(CompanyRole.POTENTIAL_PARTNER)).isFalse();
    }

    @Test
    void submitFailsIfNotAssigned() {
        assertThatThrownBy(() -> strategy.submit(draft, task, null, new SubmitRoleEvaluationRequest(), 999L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only assigned staff");
    }

    @Test
    void submitFailsIfIncomplete() {
        com.apms.domain.score.dto.draft.RoleEvaluationReadinessResponse readiness = com.apms.domain.score.dto.draft.RoleEvaluationReadinessResponse.builder()
                .staffMaySubmit(false)
                .aggregateCompletenessStatus(EvaluationCompletenessStatus.INCOMPLETE)
                .build();
        when(sufficiencyEvaluator.evaluate(draft)).thenReturn(readiness);

        assertThatThrownBy(() -> strategy.submit(draft, task, null, new SubmitRoleEvaluationRequest(), 100L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Data is INCOMPLETE");
    }

    @Test
    void submitSucceedsIfCompleteAndUpdatesDraft() {
        com.apms.domain.score.dto.draft.RoleEvaluationReadinessResponse readiness = com.apms.domain.score.dto.draft.RoleEvaluationReadinessResponse.builder()
                .staffMaySubmit(true)
                .aggregateCompletenessStatus(EvaluationCompletenessStatus.COMPLETE)
                .build();
        when(sufficiencyEvaluator.evaluate(draft)).thenReturn(readiness);

        com.mongodb.client.result.UpdateResult mockResult = mock(com.mongodb.client.result.UpdateResult.class);
        when(mockResult.getModifiedCount()).thenReturn(1L);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(RoleEvaluationDraft.class))).thenReturn(mockResult);

        strategy.submit(draft, task, null, new SubmitRoleEvaluationRequest(), 100L);

        assertThat(draft.getStatus()).isEqualTo(RoleEvaluationStatus.IN_REVIEW);
        verify(mongoTemplate).insert(any(com.apms.domain.score.outbox.RoleEvaluationOutboxEvent.class));
    }
}
