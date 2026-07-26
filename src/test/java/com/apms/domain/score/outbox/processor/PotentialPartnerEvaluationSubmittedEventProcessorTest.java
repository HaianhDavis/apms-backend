package com.apms.domain.score.outbox.processor;

import com.apms.common.enums.SubmissionStatus;
import com.apms.common.enums.TaskStatus;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
import com.apms.domain.score.enums.RoleEvaluationOutboxEventType;
import com.apms.domain.score.outbox.RoleEvaluationOutboxEvent;
import com.apms.domain.score.outbox.RoleEvaluationOutboxPayload;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class PotentialPartnerEvaluationSubmittedEventProcessorTest {

    @Mock private AccountRepository accountRepository;
    @Mock private ProjectTaskRepository taskRepository;
    @Mock private ProjectTaskSubmissionRepository submissionRepository;
    @Mock private AuditLogService auditLogService;

    @InjectMocks
    private PotentialPartnerEvaluationSubmittedEventProcessor processor;

    @Test
    void testProcessSubmittedEvent() {
        RoleEvaluationOutboxPayload payload = RoleEvaluationOutboxPayload.builder()
                .actorAccountId(100L)
                .build();
        RoleEvaluationOutboxEvent event = RoleEvaluationOutboxEvent.builder()
                .taskId(20L)
                .evaluationId("eval-1")
                .payload(payload)
                .build();

        when(accountRepository.findById(100L)).thenReturn(Optional.of(new Account()));
        
        ProjectTask task = new ProjectTask();
        when(taskRepository.findById(20L)).thenReturn(Optional.of(task));

        processor.process(event);

        verify(submissionRepository).save(any(ProjectTaskSubmission.class));
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_REVIEW);
        verify(taskRepository).save(task);
        verify(auditLogService).log(any(), any(), any(), any(), any());
    }
}
