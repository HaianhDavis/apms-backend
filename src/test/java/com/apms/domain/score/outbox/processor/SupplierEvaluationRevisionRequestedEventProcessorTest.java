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
import org.junit.jupiter.api.BeforeEach;
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
public class SupplierEvaluationRevisionRequestedEventProcessorTest {

    @Mock private ProjectTaskRepository taskRepository;
    @Mock private ProjectTaskSubmissionRepository submissionRepository;
    @Mock private AuditLogService auditLogService;

    @InjectMocks
    private SupplierEvaluationRevisionRequestedEventProcessor processor;

    private RoleEvaluationOutboxEvent event;
    private ProjectTask task;
    private ProjectTaskSubmission submission;

    @BeforeEach
    void setUp() {
        RoleEvaluationOutboxPayload payload = RoleEvaluationOutboxPayload.builder()
                .submissionId(30L)
                .actorAccountId(100L)
                .managerFeedback("Need more info")
                .build();

        event = RoleEvaluationOutboxEvent.builder()
                .id("event-1")
                .taskId(20L)
                .evaluationId("eval-1")
                .payload(payload)
                .build();

        task = new ProjectTask();
        submission = new ProjectTaskSubmission();
    }

    @Test
    void testProcessRevisionRequestedEvent() {
        when(taskRepository.findById(20L)).thenReturn(Optional.of(task));
        when(submissionRepository.findById(30L)).thenReturn(Optional.of(submission));

        processor.process(event);

        assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.REJECTED);
        verify(submissionRepository).save(submission);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(task.getCompletedAt()).isNull();
        verify(taskRepository).save(task);
        verify(auditLogService).log(any(), any(), any(), any(), any());
    }

    @Test
    void testSupports() {
        assertThat(processor.supports(RoleEvaluationOutboxEventType.SUPPLIER_EVALUATION_REVISION_REQUESTED)).isTrue();
    }
}
