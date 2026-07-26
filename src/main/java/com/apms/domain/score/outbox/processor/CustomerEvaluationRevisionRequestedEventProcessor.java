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
import com.apms.domain.score.outbox.RoleEvaluationOutboxEventProcessorStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CustomerEvaluationRevisionRequestedEventProcessor implements RoleEvaluationOutboxEventProcessorStrategy {

    private final ProjectTaskRepository taskRepository;
    private final ProjectTaskSubmissionRepository submissionRepository;
    private final AuditLogService auditLogService;

    @Override
    public boolean supports(RoleEvaluationOutboxEventType eventType) {
        return eventType == RoleEvaluationOutboxEventType.CUSTOMER_EVALUATION_REVISION_REQUESTED;
    }

    @Override
    public void process(RoleEvaluationOutboxEvent event) {
        ProjectTask task = taskRepository.findById(event.getTaskId())
                .orElseThrow(() -> new IllegalStateException("Task not found: " + event.getTaskId()));

        ProjectTaskSubmission submission = submissionRepository.findById(event.getPayload().getSubmissionId())
                .orElseThrow(() -> new IllegalStateException("Submission not found: " + event.getPayload().getSubmissionId()));

        submission.setStatus(SubmissionStatus.REJECTED);
        submissionRepository.save(submission);

        task.setStatus(TaskStatus.IN_PROGRESS);
        task.setCompletedAt(null);
        taskRepository.save(task);

        auditLogService.log(event.getPayload().getActorAccountId(), AuditAction.ROLE_EVALUATION_REVISION_REQUESTED,
                "ROLE_EVALUATION_DRAFT", event.getEvaluationId(),
                "Revision requested for CUSTOMER evaluation: " + event.getPayload().getManagerFeedback());
    }
}
