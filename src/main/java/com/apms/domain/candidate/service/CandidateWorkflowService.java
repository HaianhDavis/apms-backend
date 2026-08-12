package com.apms.domain.candidate.service;

import com.apms.common.enums.CandidateStatus;
import com.apms.common.enums.SubmissionStatus;
import com.apms.common.enums.SubmissionType;
import com.apms.common.enums.TaskStatus;
import com.apms.common.exception.BusinessValidationException;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.candidate.dto.CandidateResponse;
import com.apms.domain.candidate.dto.CandidateWorkflowResponse;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import com.apms.common.enums.AuditAction;
import com.apms.domain.audit.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class CandidateWorkflowService {

    private final CandidateService candidateService;
    private final ProjectTaskRepository taskRepository;
    private final ProjectTaskSubmissionRepository submissionRepository;
    private final AccountRepository accountRepository;
    private final AuditLogService auditLogService;
    private final com.apms.domain.notification.service.NotificationService notificationService;

    /**
     * Centralized orchestrator to submit a candidate for manager review.
     * Guarantees atomic transition of Candidate (Mongo) and Task (Postgres).
     */
    @Transactional
    public CandidateWorkflowResponse submitCandidateForReview(String candidateId, Long taskId, Long submitterId) {
        log.info("Starting workflow submit for Candidate {} and Task {}", candidateId, taskId);

        // 1. Fetch related SQL entities
        ProjectTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found"));
        Account submitter = accountRepository.findById(submitterId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));

        if (task.getStatus() == TaskStatus.DONE || task.getStatus() == TaskStatus.CANCELLED) {
            throw new IllegalStateException("Cannot submit work for a task that is DONE or CANCELLED");
        }

        // 2. Validate existing submissions to prevent duplicate active reviews
        boolean hasInReviewOrSubmitted = submissionRepository.findByProjectTask_Id(taskId).stream()
                .anyMatch(s -> s.getStatus() == SubmissionStatus.IN_REVIEW);
        if (hasInReviewOrSubmitted) {
            throw new BusinessValidationException("This task already has a draft under review.");
        }

        // 3. Delegate Mongo updates to CandidateService
        // This will validate Staff confirmations, increment revision, and change status to PENDING_REVIEW
        CandidateResponse draft = candidateService.submitCandidate(candidateId, submitterId);
        Integer revision = draft.getRevisionNumber();

        // 4. Update Postgres state (Task and Submission)
        ProjectTaskSubmission submission = ProjectTaskSubmission.builder()
                .projectTask(task)
                .project(task.getProject())
                .submittedByAccount(submitter)
                .submissionType(SubmissionType.COMPANY_CANDIDATE)
                .targetEntityType("CompanyCandidate")
                .targetEntityId(candidateId)
                .status(SubmissionStatus.IN_REVIEW)
                .note("Candidate submitted for manager review via centralized workflow.")
                .submittedRevisionNumber(revision)
                .submittedAt(LocalDateTime.now())
                .build();
                
        submission = submissionRepository.saveAndFlush(submission);

        task.setStatus(TaskStatus.IN_REVIEW);
        task.setCompletedAt(null);
        taskRepository.saveAndFlush(task);

        // 5. Audit Logging
        auditLogService.log(
                submitterId,
                AuditAction.PROJECT_TASK_SUBMITTED,
                "ProjectTask",
                String.valueOf(task.getId()),
                "Task submitted for review via centralized workflow");

        log.info("Successfully transitioned Candidate {} and Task {} to IN_REVIEW", candidateId, taskId);

        return CandidateWorkflowResponse.builder()
                .candidateId(candidateId)
                .candidateStatus(draft.getStatus())
                .taskId(taskId)
                .taskStatus(task.getStatus())
                .submissionId(submission.getId())
                .submissionStatus(submission.getStatus())
                .reviewRound(revision)
                .candidateDetail(draft)
                .build();
    }

    @Transactional
    public void repairInvalidState(Long taskId) {
        ProjectTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found"));

        if (task.getStatus() == TaskStatus.IN_PROGRESS) {
            boolean hasInReviewSubmission = submissionRepository.findByProjectTask_Id(taskId).stream()
                    .anyMatch(s -> s.getStatus() == SubmissionStatus.IN_REVIEW);

            if (hasInReviewSubmission) {
                log.info("Repairing task {} state to IN_REVIEW due to existing IN_REVIEW submission", taskId);
                task.setStatus(TaskStatus.IN_REVIEW);
                taskRepository.saveAndFlush(task);
            }
        }
    }
    @Transactional
    public CandidateWorkflowResponse managerRejectCandidate(String candidateId, Long reviewerId, String comment) {
        log.info("Starting workflow reject for Candidate {}", candidateId);

        com.apms.domain.candidate.CompanyCandidate candidate = candidateService.findCandidateOrThrow(candidateId);
        Long taskId = candidate.getTaskId();
        if (taskId == null) {
            throw new BusinessValidationException("Candidate is not associated with a task.");
        }

        ProjectTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found"));

        ProjectTaskSubmission submission = submissionRepository.findByProjectTask_Id(taskId).stream()
                .filter(s -> s.getStatus() == SubmissionStatus.IN_REVIEW && "CompanyCandidate".equals(s.getTargetEntityType()) && candidateId.equals(s.getTargetEntityId()))
                .findFirst()
                .orElse(null);

        if (submission != null) {
            submission.setStatus(SubmissionStatus.CHANGES_REQUESTED);
            submission.setReviewComment(comment);
            submission.setReviewedByAccount(accountRepository.findById(reviewerId).orElse(null));
            submission.setReviewedAt(LocalDateTime.now());
            submissionRepository.saveAndFlush(submission);
        }

        task.setStatus(TaskStatus.IN_PROGRESS);
        task.setCompletedAt(null);
        taskRepository.saveAndFlush(task);

        CandidateResponse draft = candidateService.sendBackCandidate(candidateId, reviewerId);
        
        auditLogService.log(
                reviewerId,
                AuditAction.PROJECT_TASK_SUBMISSION_REJECTED,
                "ProjectTask",
                String.valueOf(task.getId()),
                "Manager rejected candidate via workflow");

        log.info("Successfully rejected Candidate {} and transitioned Task {} to IN_PROGRESS", candidateId, taskId);

        try {
            Account recipient = submission != null && submission.getSubmittedByAccount() != null
                    ? submission.getSubmittedByAccount()
                    : task.getAssignedToAccount();
            Account reviewer = accountRepository.findById(reviewerId).orElse(null);
            if (recipient != null) {
                notificationService.notifyTaskChangesRequested(task, submission, recipient, reviewer, comment);
            }
        } catch (Exception ex) {
            log.warn("Failed to send notification for candidate rejection: {}", ex.getMessage());
        }

        return CandidateWorkflowResponse.builder()
                .candidateId(candidateId)
                .candidateStatus(draft.getStatus())
                .taskId(taskId)
                .taskStatus(task.getStatus())
                .submissionId(submission != null ? submission.getId() : null)
                .submissionStatus(submission != null ? submission.getStatus() : null)
                .reviewRound(draft.getRevisionNumber())
                .candidateDetail(draft)
                .build();
    }

    @Transactional
    public CandidateWorkflowResponse managerApproveCandidate(String candidateId, Long reviewerId, String comment) {
        log.info("Starting workflow approve for Candidate {}", candidateId);

        com.apms.domain.candidate.CompanyCandidate candidate = candidateService.findCandidateOrThrow(candidateId);
        Long taskId = candidate.getTaskId();
        if (taskId == null) {
            throw new BusinessValidationException("Candidate is not associated with a task.");
        }

        ProjectTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found"));
        Account reviewer = accountRepository.findById(reviewerId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));

        java.util.List<ProjectTaskSubmission> candidateSubmissions = submissionRepository.findByProjectTask_Id(taskId).stream()
                .filter(s -> "CompanyCandidate".equals(s.getTargetEntityType()) && candidateId.equals(s.getTargetEntityId()))
                .toList();

        ProjectTaskSubmission submission = candidateSubmissions.stream()
                .filter(s -> s.getStatus() == SubmissionStatus.IN_REVIEW)
                .findFirst()
                .orElseGet(() -> candidateSubmissions.stream()
                        .filter(s -> s.getStatus() == SubmissionStatus.APPROVED)
                        .findFirst()
                        .orElse(null));

        if (submission == null) {
            throw new BusinessValidationException("No active candidate submission found for final approval.");
        }

        candidateService.validateFinalReviewReadiness(candidateId, com.apms.common.enums.ReviewDecision.APPROVE);
        CandidateResponse approved = candidateService.approveCandidate(
                candidateId,
                new com.apms.domain.candidate.dto.ApproveCandidateRequest(),
                reviewerId);

        LocalDateTime now = LocalDateTime.now();
        submission.setStatus(SubmissionStatus.APPROVED);
        submission.setReviewComment(comment);
        submission.setReviewedByAccount(reviewer);
        submission.setReviewedAt(now);
        submissionRepository.saveAndFlush(submission);

        task.setStatus(TaskStatus.DONE);
        task.setCompletedAt(now);
        taskRepository.saveAndFlush(task);

        auditLogService.log(
                reviewerId,
                AuditAction.PROJECT_TASK_SUBMISSION_APPROVED,
                "ProjectTaskSubmission",
                String.valueOf(submission.getId()),
                "Manager approved candidate via workflow");

        log.info("Successfully approved Candidate {} and transitioned Task {} to DONE", candidateId, taskId);

        return CandidateWorkflowResponse.builder()
                .candidateId(candidateId)
                .candidateStatus(approved.getStatus())
                .taskId(taskId)
                .taskStatus(task.getStatus())
                .submissionId(submission.getId())
                .submissionStatus(submission.getStatus())
                .reviewRound(approved.getRevisionNumber())
                .candidateDetail(approved)
                .build();
    }

    @Transactional
    public void reconcileSendBackInconsistency(String candidateId) {
        com.apms.domain.candidate.CompanyCandidate candidate = candidateService.findCandidateOrThrow(candidateId);
        if (candidate.getStatus() != com.apms.common.enums.CandidateStatus.REVISION_REQUIRED) {
            log.info("Candidate {} is not REVISION_REQUIRED, skipping reconciliation.", candidateId);
            return;
        }

        Long taskId = candidate.getTaskId();
        if (taskId == null) return;

        ProjectTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null || task.getStatus() != TaskStatus.IN_REVIEW) {
            log.info("Task {} is not IN_REVIEW, skipping reconciliation.", taskId);
            return;
        }

        log.info("Found Send Back inconsistency for Candidate {} / Task {}. Repairing...", candidateId, taskId);

        ProjectTaskSubmission submission = submissionRepository.findByProjectTask_Id(taskId).stream()
                .filter(s -> s.getStatus() == SubmissionStatus.IN_REVIEW && "CompanyCandidate".equals(s.getTargetEntityType()) && candidateId.equals(s.getTargetEntityId()))
                .findFirst()
                .orElse(null);

        if (submission != null) {
            submission.setStatus(SubmissionStatus.CHANGES_REQUESTED);
            submission.setReviewComment("System Reconciled: Manager Send Back");
            submissionRepository.saveAndFlush(submission);
            log.info("Reconciled Submission {} to CHANGES_REQUESTED", submission.getId());
        }

        task.setStatus(TaskStatus.IN_PROGRESS);
        taskRepository.saveAndFlush(task);
        log.info("Reconciled Task {} to IN_PROGRESS", taskId);
    }
}
