package com.apms.domain.score.outbox.processor;

import com.apms.common.enums.AuditAction;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class PartnerEvaluationRevisionRequestedEventProcessorTest {

    @Mock
    private ProjectTaskRepository taskRepository;

    @Mock
    private ProjectTaskSubmissionRepository submissionRepository;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private PartnerEvaluationRevisionRequestedEventProcessor processor;

    private RoleEvaluationOutboxEvent event;
    private ProjectTask task;
    private ProjectTaskSubmission submission;

    @BeforeEach
    void setUp() {
        RoleEvaluationOutboxPayload payload = new RoleEvaluationOutboxPayload();
        payload.setActorAccountId(100L);
        payload.setSubmissionId(200L);
        payload.setManagerFeedback("Please revise");

        event = new RoleEvaluationOutboxEvent();
        event.setEvaluationId("draft-1");
        event.setTaskId(300L);
        event.setEventType(RoleEvaluationOutboxEventType.PARTNER_EVALUATION_REVISION_REQUESTED);
        event.setPayload(payload);

        task = new ProjectTask();
        task.setId(300L);
        task.setStatus(TaskStatus.IN_REVIEW);

        submission = new ProjectTaskSubmission();
        submission.setId(200L);
        submission.setStatus(SubmissionStatus.IN_REVIEW);
    }

    @Test
    void testSupports() {
        assertTrue(processor.supports(RoleEvaluationOutboxEventType.PARTNER_EVALUATION_REVISION_REQUESTED));
    }

    @Test
    void testProcessSuccessfully() {
        when(taskRepository.findById(300L)).thenReturn(Optional.of(task));
        when(submissionRepository.findById(200L)).thenReturn(Optional.of(submission));

        processor.process(event);

        assertEquals(SubmissionStatus.REJECTED, submission.getStatus());
        verify(submissionRepository).save(submission);

        assertEquals(TaskStatus.IN_PROGRESS, task.getStatus());
        assertNull(task.getCompletedAt());
        verify(taskRepository).save(task);

        verify(auditLogService).log(eq(100L), eq(AuditAction.PARTNER_EVALUATION_REVISION_REQUESTED),
                eq("ROLE_EVALUATION_DRAFT"), eq("draft-1"), eq("Revision requested: Please revise"));
        verify(auditLogService, org.mockito.Mockito.never()).log(eq(100L), eq(AuditAction.ROLE_EVALUATION_REVISION_REQUESTED),
                any(), any(), any());
    }

    @Test
    void testProcessThrowsIfTaskNotFound() {
        when(taskRepository.findById(300L)).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> processor.process(event));
    }

    @Test
    void testProcessThrowsIfSubmissionNotFound() {
        when(taskRepository.findById(300L)).thenReturn(Optional.of(task));
        when(submissionRepository.findById(200L)).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> processor.process(event));
    }
}
