package com.apms.domain.score.outbox.processor;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.SubmissionStatus;
import com.apms.common.enums.SubmissionType;
import com.apms.common.enums.TaskStatus;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
import com.apms.domain.score.enums.RoleEvaluationOutboxEventType;
import com.apms.domain.score.outbox.RoleEvaluationOutboxEvent;
import com.apms.domain.score.outbox.RoleEvaluationOutboxEventProcessorStrategy;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class PotentialPartnerEvaluationSubmittedEventProcessor implements RoleEvaluationOutboxEventProcessorStrategy {

    private final AccountRepository accountRepository;
    private final ProjectTaskRepository taskRepository;
    private final ProjectTaskSubmissionRepository submissionRepository;
    private final AuditLogService auditLogService;

    @Override
    public boolean supports(RoleEvaluationOutboxEventType eventType) {
        return eventType == RoleEvaluationOutboxEventType.POTENTIAL_PARTNER_EVALUATION_SUBMITTED;
    }

    @Override
    public void process(RoleEvaluationOutboxEvent event) {
        Account account = accountRepository.findById(event.getPayload().getActorAccountId())
                .orElseThrow(() -> new IllegalStateException("Account not found: " + event.getPayload().getActorAccountId()));

        ProjectTask task = taskRepository.findById(event.getTaskId())
                .orElseThrow(() -> new IllegalStateException("Task not found: " + event.getTaskId()));

        ProjectTaskSubmission submission = ProjectTaskSubmission.builder()
                .project(task.getProject())
                .projectTask(task)
                .submissionType(SubmissionType.ROLE_EVALUATION)
                .targetEntityType("ROLE_EVALUATION_DRAFT")
                .targetEntityId(event.getEvaluationId())
                .status(SubmissionStatus.IN_REVIEW)
                .submittedByAccount(account)
                .submittedAt(LocalDateTime.now())
                .build();
        submissionRepository.save(submission);

        task.setStatus(TaskStatus.IN_REVIEW);
        taskRepository.save(task);

        auditLogService.log(event.getPayload().getActorAccountId(), AuditAction.ROLE_EVALUATION_SUBMITTED,
                "ROLE_EVALUATION_DRAFT", event.getEvaluationId(),
                "Submitted POTENTIAL_PARTNER evaluation for review");
    }
}
