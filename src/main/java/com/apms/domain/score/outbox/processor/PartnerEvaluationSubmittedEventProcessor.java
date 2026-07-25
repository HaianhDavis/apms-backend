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

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PartnerEvaluationSubmittedEventProcessor implements RoleEvaluationOutboxEventProcessorStrategy {

    private final ProjectTaskRepository taskRepository;
    private final ProjectTaskSubmissionRepository submissionRepository;
    private final AccountRepository accountRepository;
    private final AuditLogService auditLogService;

    @Override
    public boolean supports(RoleEvaluationOutboxEventType eventType) {
        return eventType == RoleEvaluationOutboxEventType.PARTNER_EVALUATION_SUBMITTED;
    }

    @Override
    public void process(RoleEvaluationOutboxEvent event) {
        ProjectTask task = taskRepository.findById(event.getTaskId())
                .orElseThrow(() -> new IllegalStateException("Task not found: " + event.getTaskId()));

        Account account = accountRepository.findById(event.getPayload().getActorAccountId())
                .orElseThrow(() -> new IllegalStateException("Account not found: " + event.getPayload().getActorAccountId()));

        // Create submission if it does not exist
        List<ProjectTaskSubmission> existingSubmissions = submissionRepository.findByProjectTask_Id(task.getId());
        ProjectTaskSubmission submission = existingSubmissions.stream()
                .filter(s -> java.util.Objects.equals(s.getTargetEntityId(), event.getEvaluationId()))
                .findFirst()
                .orElseGet(() -> ProjectTaskSubmission.builder()
                        .project(task.getProject())
                        .projectTask(task)
                        .submissionType(SubmissionType.ROLE_EVALUATION)
                        .targetEntityType("ROLE_EVALUATION_DRAFT")
                        .targetEntityId(event.getEvaluationId())
                        .submittedByAccount(account)
                        .build());

        submission.setStatus(SubmissionStatus.IN_REVIEW);
        submission.setSubmittedAt(LocalDateTime.now());
        submissionRepository.save(submission);

        task.setStatus(TaskStatus.IN_REVIEW);
        task.setCompletedAt(null);
        taskRepository.save(task);

        auditLogService.log(account.getId(), AuditAction.PARTNER_EVALUATION_SUBMITTED, "ROLE_EVALUATION_DRAFT", event.getEvaluationId(),
                "Submitted partner evaluation for review");
    }
}
