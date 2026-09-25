package com.apms.domain.notification.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.NotificationType;
import com.apms.common.enums.SystemRole;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.notification.Notification;
import com.apms.domain.notification.dto.NotificationResponse;
import com.apms.domain.notification.dto.SendNotificationRequest;
import com.apms.domain.notification.repository.sql.NotificationRepository;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import java.util.Optional;
import com.apms.domain.profile.assessment.CompanyRelationshipAssessment;
import com.apms.domain.profile.assessment.RelationshipAssessmentStatus;
import com.apms.domain.profile.assessment.repository.CompanyRelationshipAssessmentRepository;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import com.apms.security.UserDetailsImpl;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final AccountRepository accountRepository;
    private final AuditLogService auditLogService;
    private final CompanyProfileRepository companyProfileRepository;
    private final CompanyRelationshipAssessmentRepository assessmentRepository;

    @Transactional(readOnly = true)
    public Page<NotificationResponse> getNotifications(Boolean unreadOnly, NotificationType type, Long targetUserId, Pageable pageable) {
        UserDetailsImpl currentUser = getCurrentUser();
        if (currentUser == null) throw new AccessDeniedException("Unauthorized");

        boolean isAdmin = hasRole(currentUser, SystemRole.SYSTEM_ADMIN);
        Long queryUserId = currentUser.getId();

        if (targetUserId != null) {
            if (!isAdmin && !targetUserId.equals(currentUser.getId())) {
                throw new AccessDeniedException("Only admins can view other users' notifications");
            }
            queryUserId = targetUserId;
        }

        final Long finalUserId = queryUserId;

        Specification<Notification> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("recipientAccount").get("id"), finalUserId));
            predicates.add(cb.equal(root.get("isDeleted"), false));

            if (Boolean.TRUE.equals(unreadOnly)) {
                predicates.add(cb.equal(root.get("isRead"), false));
            }
            if (type != null) {
                predicates.add(cb.equal(root.get("type"), type));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return notificationRepository.findAll(spec, pageable).map(this::toResponse);
    }

    @Transactional
    public void markAsRead(Long notificationId) {
        Notification notification = getNotificationWithAccessCheck(notificationId);

        if (Boolean.FALSE.equals(notification.getIsRead())) {
            notification.setIsRead(true);
            notification.setReadAt(LocalDateTime.now());
            notificationRepository.save(notification);

            UserDetailsImpl currentUser = getCurrentUser();
            if (currentUser != null) {
                auditLogService.log(currentUser.getId(), AuditAction.NOTIFICATION_READ, "Notification", String.valueOf(notificationId), "Notification marked as read");
            }
        }
    }

    @Transactional
    public void markAllAsRead() {
        UserDetailsImpl currentUser = getCurrentUser();
        if (currentUser == null) throw new AccessDeniedException("Unauthorized");

        Specification<Notification> spec = (root, query, cb) -> cb.and(
                cb.equal(root.get("recipientAccount").get("id"), currentUser.getId()),
                cb.equal(root.get("isDeleted"), false),
                cb.equal(root.get("isRead"), false)
        );

        List<Notification> unread = notificationRepository.findAll(spec);
        if (!unread.isEmpty()) {
            LocalDateTime now = LocalDateTime.now();
            unread.forEach(n -> {
                n.setIsRead(true);
                n.setReadAt(now);
            });
            notificationRepository.saveAll(unread);

            auditLogService.log(currentUser.getId(), AuditAction.NOTIFICATION_READ, "Notification", "ALL", "All notifications marked as read");
        }
    }

    @Transactional
    public void markAsReadBatch(List<Long> notificationIds) {
        if (notificationIds == null || notificationIds.isEmpty()) {
            return;
        }

        UserDetailsImpl currentUser = getCurrentUser();
        if (currentUser == null) throw new AccessDeniedException("Unauthorized");

        List<Notification> notifications = notificationRepository.findAllById(notificationIds);
        if (notifications.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        List<Notification> toUpdate = new ArrayList<>();

        for (Notification n : notifications) {
            if (!n.getRecipientAccount().getId().equals(currentUser.getId()) && !hasRole(currentUser, SystemRole.SYSTEM_ADMIN)) {
                throw new AccessDeniedException("Access denied to notification " + n.getId());
            }

            if (Boolean.FALSE.equals(n.getIsRead()) && Boolean.FALSE.equals(n.getIsDeleted())) {
                n.setIsRead(true);
                n.setReadAt(now);
                toUpdate.add(n);
            }
        }

        if (!toUpdate.isEmpty()) {
            notificationRepository.saveAll(toUpdate);
            auditLogService.log(currentUser.getId(), AuditAction.NOTIFICATION_READ, "Notification", "BATCH", "Marked " + toUpdate.size() + " notifications as read");
        }
    }

    @Transactional
    public void deleteNotification(Long notificationId) {
        Notification notification = getNotificationWithAccessCheck(notificationId);

        if (Boolean.FALSE.equals(notification.getIsDeleted())) {
            notification.setIsDeleted(true);
            notificationRepository.save(notification);

            UserDetailsImpl currentUser = getCurrentUser();
            if (currentUser != null) {
                auditLogService.log(currentUser.getId(), AuditAction.NOTIFICATION_DELETED, "Notification", String.valueOf(notificationId), "Notification deleted");
            }
        }
    }

    @Transactional
    public NotificationResponse sendNotification(SendNotificationRequest request) {
        UserDetailsImpl currentUser = getCurrentUser();
        if (currentUser == null || !hasRole(currentUser, SystemRole.SYSTEM_ADMIN)) {
            throw new AccessDeniedException("Only SYSTEM_ADMIN can send notifications manually");
        }

        Account sender = accountRepository.findById(currentUser.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Sender account not found"));

        Account recipient = accountRepository.findById(request.getRecipientUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Recipient account not found"));

        Notification notification = Notification.builder()
                .recipientAccount(recipient)
                .senderAccount(sender)
                .title(request.getTitle())
                .message(request.getMessage())
                .type(request.getType())
                .isRead(false)
                .isDeleted(false)
                .build();

        notification = notificationRepository.save(notification);

        auditLogService.log(currentUser.getId(), AuditAction.NOTIFICATION_SENT, "Notification", String.valueOf(notification.getId()), "Notification sent to user " + recipient.getId());

        return toResponse(notification);
    }

    @Transactional
    public void notifyTaskAssigned(ProjectTask task, Account recipient, Account sender) {
        if (task == null || recipient == null) {
            return;
        }

        String projectName = task.getProject() != null ? task.getProject().getProjectName() : "Project";
        String title = "You have a new task";
        String message = String.format("%s - %s", projectName, task.getTitle());
        Long projectId = task.getProject() != null ? task.getProject().getId() : null;
        Notification notification = createSystemNotification(
                recipient, sender, title, message, NotificationType.TASK, projectId, task.getId(), null, "TASK_ASSIGNED");

        runAfterCommit(() -> pushToUser(
                recipient.getId(),
                title,
                message,
                java.util.Map.of(
                        "type", "TASK_ASSIGNED",
                        "notificationId", String.valueOf(notification.getId()),
                        "projectId", String.valueOf(task.getProject() != null ? task.getProject().getId() : ""),
                        "taskId", String.valueOf(task.getId())
                )));
    }

    @Transactional
    public void notifyProjectMemberAdded(Project project, Account recipient, Account sender) {
        if (project == null || recipient == null) {
            return;
        }

        String title = "You were added to a project";
        String message = project.getProjectName();
        Notification notification = createSystemNotification(
                recipient, sender, title, message, NotificationType.SYSTEM, project.getId(), null, null, "PROJECT_MEMBER_ADDED");

        runAfterCommit(() -> pushToUser(
                recipient.getId(),
                title,
                message,
                java.util.Map.of(
                        "type", "PROJECT_MEMBER_ADDED",
                        "notificationId", String.valueOf(notification.getId()),
                        "projectId", String.valueOf(project.getId())
                )));
    }

    @Transactional
    public void notifyTasksAvailable(Project project, Account recipient, Account sender) {
        if (project == null || recipient == null) {
            return;
        }

        String title = "New tasks are available";
        String message = String.format("New tasks are available in Project %s.", project.getProjectName());
        Notification notification = createSystemNotification(
                recipient, sender, title, message, NotificationType.TASK, project.getId(), null, null, "TASKS_AVAILABLE");

        runAfterCommit(() -> pushToUser(
                recipient.getId(),
                title,
                message,
                java.util.Map.of(
                        "type", "TASKS_AVAILABLE",
                        "notificationId", String.valueOf(notification.getId()),
                        "projectId", String.valueOf(project.getId())
                )));
    }

    @Transactional
    public void notifyTaskSubmitted(ProjectTask task, Account recipient, Account sender) {
        if (task == null || recipient == null) {
            return;
        }

        String projectName = task.getProject() != null ? task.getProject().getProjectName() : "Project";
        String title = "Task submitted for review";
        String message = String.format("Task submitted: %s (%s)", task.getTitle(), projectName);
        Long projectId = task.getProject() != null ? task.getProject().getId() : null;
        Notification notification = createSystemNotification(
                recipient, sender, title, message, NotificationType.TASK, projectId, task.getId(), null, "TASK_SUBMITTED");

        runAfterCommit(() -> pushToUser(
                recipient.getId(),
                title,
                message,
                java.util.Map.of(
                        "type", "TASK_SUBMITTED",
                        "notificationId", String.valueOf(notification.getId()),
                        "projectId", String.valueOf(task.getProject() != null ? task.getProject().getId() : ""),
                        "taskId", String.valueOf(task.getId())
                )));
    }

    @Transactional
    public void notifyTaskApproved(ProjectTask task, Account recipient, Account sender) {
        if (task == null || recipient == null) {
            return;
        }

        String projectName = task.getProject() != null ? task.getProject().getProjectName() : "Project";
        String title = "Task approved";
        String message = String.format("Task approved: %s (%s)", task.getTitle(), projectName);
        Long projectId = task.getProject() != null ? task.getProject().getId() : null;
        Notification notification = createSystemNotification(
                recipient, sender, title, message, NotificationType.TASK, projectId, task.getId(), null, "TASK_APPROVED");

        runAfterCommit(() -> pushToUser(
                recipient.getId(),
                title,
                message,
                java.util.Map.of(
                        "type", "TASK_APPROVED",
                        "notificationId", String.valueOf(notification.getId()),
                        "projectId", String.valueOf(task.getProject() != null ? task.getProject().getId() : ""),
                        "taskId", String.valueOf(task.getId())
                )));
    }

    @Transactional
    public void notifyDocumentRejected(ProjectTaskSubmission submission, String documentId, String documentName, Long reviewerId, String rejectReason) {
        if (submission == null || reviewerId == null) {
            return;
        }

        Account recipient = submission.getSubmittedByAccount();
        if (recipient == null && submission.getProjectTask() != null) {
            recipient = submission.getProjectTask().getAssignedToAccount();
        }
        if (recipient == null || recipient.getId().equals(reviewerId)) {
            return;
        }

        Account reviewer = accountRepository.findById(reviewerId).orElse(null);
        ProjectTask task = submission.getProjectTask();
        Project project = submission.getProject();
        Long projectId = project != null ? project.getId() : null;
        Long taskId = task != null ? task.getId() : null;
        String actionType = "DOCUMENT_REJECTED";
        String normalizedDocumentId = StringUtils.hasText(documentId) ? documentId.trim() : null;

        if (notificationRepository.existsDocumentActionNotification(
                recipient.getId(),
                NotificationType.DOCUMENT,
                actionType,
                projectId,
                taskId,
                submission.getId(),
                normalizedDocumentId)) {
            log.debug("Skipping duplicate document rejection notification for submission={}, document={}",
                    submission.getId(), normalizedDocumentId);
            return;
        }

        String safeDocumentName = StringUtils.hasText(documentName)
                ? documentName.trim()
                : (StringUtils.hasText(normalizedDocumentId) ? normalizedDocumentId : "Document package");
        String reviewerLabel = reviewer != null && StringUtils.hasText(reviewer.getEmail())
                ? reviewer.getEmail()
                : "the Manager";
        String title = "Document rejected";
        String message = safeDocumentName + " was rejected by " + reviewerLabel + ".";
        if (StringUtils.hasText(rejectReason)) {
            message += "\nReason: " + rejectReason.trim();
        }
        if (project != null && StringUtils.hasText(project.getProjectName())) {
            message += "\nProject: " + project.getProjectName();
        }

        Notification notification = Notification.builder()
                .recipientAccount(recipient)
                .senderAccount(reviewer)
                .title(title)
                .message(message)
                .type(NotificationType.DOCUMENT)
                .projectId(projectId)
                .taskId(taskId)
                .submissionId(submission.getId())
                .documentId(normalizedDocumentId)
                .actionType(actionType)
                .rejectReason(StringUtils.hasText(rejectReason) ? rejectReason.trim() : null)
                .isRead(false)
                .isDeleted(false)
                .build();

        notification = notificationRepository.save(notification);
        auditLogService.log(
                reviewerId,
                AuditAction.DOCUMENT_REJECTED,
                "ProjectTaskSubmission",
                String.valueOf(submission.getId()),
                "Document rejected notification sent to user " + recipient.getId()
                        + (normalizedDocumentId != null ? " for document " + normalizedDocumentId : ""));

        Notification savedNotification = notification;
        Account savedRecipient = recipient;
        String pushMessage = message;
        runAfterCommit(() -> pushToUser(
                savedRecipient.getId(),
                title,
                pushMessage,
                java.util.Map.of(
                        "type", actionType,
                        "notificationId", String.valueOf(savedNotification.getId()),
                        "projectId", projectId != null ? String.valueOf(projectId) : "",
                        "taskId", taskId != null ? String.valueOf(taskId) : "",
                        "documentId", normalizedDocumentId != null ? normalizedDocumentId : "",
                        "submissionId", String.valueOf(submission.getId())
                )));
    }

    @Transactional
    public void notifyTaskChangesRequested(ProjectTask task, ProjectTaskSubmission submission, Account recipient, Account reviewer, String reason) {
        if (task == null || recipient == null) {
            return;
        }

        if (reviewer != null && recipient.getId().equals(reviewer.getId())) {
            return;
        }

        Long projectId = task.getProject() != null ? task.getProject().getId() : null;
        Long taskId = task.getId();
        Long submissionId = submission != null ? submission.getId() : null;
        String actionType = "TASK_CHANGES_REQUESTED";

        String projectName = task.getProject() != null ? task.getProject().getProjectName() : "Project";
        String taskTitle = StringUtils.hasText(task.getTitle()) ? task.getTitle() : "Task";
        String reviewerLabel = reviewer != null && StringUtils.hasText(reviewer.getEmail())
                ? reviewer.getEmail()
                : "the Manager";

        String title = "Changes requested on task";
        String message = String.format("Manager %s requested changes on task: %s (%s).", reviewerLabel, taskTitle, projectName);
        if (StringUtils.hasText(reason)) {
            message += "\nReason: " + reason.trim();
        }

        Notification notification = Notification.builder()
                .recipientAccount(recipient)
                .senderAccount(reviewer)
                .title(title)
                .message(message)
                .type(NotificationType.TASK)
                .projectId(projectId)
                .taskId(taskId)
                .submissionId(submissionId)
                .actionType(actionType)
                .rejectReason(StringUtils.hasText(reason) ? reason.trim() : null)
                .isRead(false)
                .isDeleted(false)
                .build();

        notification = notificationRepository.save(notification);

        Notification savedNotification = notification;
        Account savedRecipient = recipient;
        String pushMessage = message;
        runAfterCommit(() -> pushToUser(
                savedRecipient.getId(),
                title,
                pushMessage,
                java.util.Map.of(
                        "type", actionType,
                        "notificationId", String.valueOf(savedNotification.getId()),
                        "projectId", projectId != null ? String.valueOf(projectId) : "",
                        "taskId", taskId != null ? String.valueOf(taskId) : ""
                )));
    }

    @Transactional
    public void notifyProjectMemberRemoved(Project project, Account recipient, Account sender) {
        if (project == null || recipient == null) {
            return;
        }

        String title = "Removed from project";
        String message = String.format("You have been removed from project \"%s\".", project.getProjectName());
        Notification notification = Notification.builder()
                .recipientAccount(recipient)
                .senderAccount(sender)
                .title(title)
                .message(message)
                .type(NotificationType.SYSTEM)
                .projectId(project.getId())
                .actionType("PROJECT_MEMBER_REMOVED")
                .isRead(false)
                .isDeleted(false)
                .build();

        notification = notificationRepository.save(notification);

        Notification savedNotification = notification;
        Account savedRecipient = recipient;
        String pushMessage = message;
        runAfterCommit(() -> pushToUser(
                savedRecipient.getId(),
                title,
                pushMessage,
                java.util.Map.of(
                        "type", "PROJECT_MEMBER_REMOVED",
                        "notificationId", String.valueOf(savedNotification.getId()),
                        "projectId", String.valueOf(project.getId())
                )));
    }

    private String resolveCompanyName(String identifier) {
        if (!StringUtils.hasText(identifier)) {
            return "Doanh nghiệp";
        }
        String trimmed = identifier.trim();
        if (companyProfileRepository != null) {
            try {
                Optional<CompanyProfile> profileOpt = companyProfileRepository.findById(trimmed)
                        .or(() -> companyProfileRepository.findByCompanyId(trimmed));
                if (profileOpt.isPresent()) {
                    String canonical = CompanyProfile.getCanonicalDisplayName(profileOpt.get(), null);
                    if (StringUtils.hasText(canonical) && !"Unknown Company".equalsIgnoreCase(canonical)) {
                        return canonical;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to resolve company name for identifier: {}", identifier, e);
            }
        }
        return "Doanh nghiệp";
    }

    @Transactional
    public void notifyCompanyProfileUpdated(CompanyProfile profile, String versionIdentity, Long actorId) {
        if (profile == null) {
            return;
        }

        String identity = StringUtils.hasText(versionIdentity) ? versionIdentity : profile.getVersionLabel();
        if (!StringUtils.hasText(identity)) {
            identity = "V1.0";
        }

        String companyProfileId = profile.getId() != null ? profile.getId() : profile.getCompanyId();
        String companyName = null;
        if (profile.getIdentity() != null) {
            if (StringUtils.hasText(profile.getIdentity().getTradeName())) {
                companyName = profile.getIdentity().getTradeName();
            } else if (StringUtils.hasText(profile.getIdentity().getLegalName())) {
                companyName = profile.getIdentity().getLegalName();
            }
        }
        if (!StringUtils.hasText(companyName) || "Unknown Company".equalsIgnoreCase(companyName)) {
            companyName = resolveCompanyName(companyProfileId);
        }

        List<Account> owners = accountRepository.findActiveAccountsByRole(SystemRole.BUSINESS_OWNER);
        if (owners == null || owners.isEmpty()) {
            log.debug("No active Business Owners to notify for profile update: {}", companyProfileId);
            return;
        }

        Account sender = actorId != null ? accountRepository.findById(actorId).orElse(null) : null;
        String title = "Hồ sơ công ty đã được cập nhật";
        String message = String.format("Thông tin của %s vừa được cập nhật trong hệ thống.", companyName);

        for (Account owner : owners) {
            if (actorId != null && owner.getId().equals(actorId)) {
                continue;
            }

            if (notificationRepository.existsLifecycleNotification(owner.getId(), "COMPANY_PROFILE_UPDATED", identity, companyProfileId)) {
                log.debug("Notification already exists for owner={}, profile={}, version={}", owner.getId(), companyProfileId, identity);
                continue;
            }

            Notification notification = Notification.builder()
                    .recipientAccount(owner)
                    .senderAccount(sender)
                    .title(title)
                    .message(message)
                    .type(NotificationType.SYSTEM)
                    .actionType("COMPANY_PROFILE_UPDATED")
                    .companyProfileId(companyProfileId)
                    .entityId(identity)
                    .entityType("COMPANY_PROFILE")
                    .isRead(false)
                    .isDeleted(false)
                    .build();

            notification = notificationRepository.save(notification);

            Notification savedNotification = notification;
            Account savedRecipient = owner;
            String pushMessage = message;
            String finalCompanyProfileId = companyProfileId;
            String finalIdentity = identity;

            runAfterCommit(() -> pushToUser(
                    savedRecipient.getId(),
                    title,
                    pushMessage,
                    java.util.Map.of(
                            "type", "COMPANY_PROFILE_UPDATED",
                            "notificationId", String.valueOf(savedNotification.getId()),
                            "companyProfileId", finalCompanyProfileId,
                            "entityId", finalIdentity
                    )));
        }
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> getUnreadRelationshipAssessmentNotifications() {
        UserDetailsImpl currentUser = getCurrentUser();
        if (currentUser == null) throw new AccessDeniedException("Unauthorized");

        List<Notification> notifications = notificationRepository.findUnreadRelationshipAssessmentNotifications(currentUser.getId());
        return notifications.stream().map(this::toResponse).toList();
    }

    @Transactional
    public void notifyRelationshipAssessmentCompleted(CompanyRelationshipAssessment assessment, Long actorId) {
        if (assessment == null) {
            return;
        }

        String entityId = String.valueOf(assessment.getId());
        String companyProfileId = assessment.getCompanyProfileId();
        List<Account> owners = accountRepository.findActiveAccountsByRole(SystemRole.BUSINESS_OWNER);
        if (owners == null || owners.isEmpty()) {
            log.debug("No active Business Owners to notify for assessment completion: {}", assessment.getId());
            return;
        }

        Account sender = actorId != null ? accountRepository.findById(actorId).orElse(null) : null;
        String companyName = resolveCompanyName(companyProfileId);

        boolean hasPreviousFinalized = assessmentRepository != null
                && StringUtils.hasText(assessment.getOwnerCompanyProfileId())
                && StringUtils.hasText(companyProfileId)
                && assessment.getId() != null
                && assessmentRepository.existsByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusAndIdNot(
                        assessment.getOwnerCompanyProfileId(),
                        companyProfileId,
                        RelationshipAssessmentStatus.FINALIZED,
                        assessment.getId()
                );

        String actionType = hasPreviousFinalized ? "RELATIONSHIP_ASSESSMENT_UPDATED" : "RELATIONSHIP_ASSESSMENT_INITIAL";
        String title = hasPreviousFinalized ? "Cập nhật đánh giá mức độ thân thiết" : "Đánh giá mức độ thân thiết mới";

        Integer score = assessment.getManagerTotalScore() != null 
                ? assessment.getManagerTotalScore() 
                : assessment.getOwnerFinalTotalScore();
        String rank = StringUtils.hasText(assessment.getManagerRank()) 
                ? assessment.getManagerRank() 
                : assessment.getOwnerFinalRank();

        String message;
        if (score != null && StringUtils.hasText(rank)) {
            message = String.format("%s · V%d · %d/100 · Rank %s", companyName, assessment.getVersionNumber(), score, rank);
        } else {
            message = String.format("%s · V%d", companyName, assessment.getVersionNumber());
        }

        for (Account owner : owners) {
            if (actorId != null && owner.getId().equals(actorId)) {
                continue;
            }

            if (notificationRepository.existsLifecycleNotification(owner.getId(), actionType, entityId, companyProfileId)
                    || notificationRepository.existsLifecycleNotification(owner.getId(), "RELATIONSHIP_ASSESSMENT_COMPLETED", entityId, companyProfileId)) {
                log.debug("Notification already exists for owner={}, assessment={}", owner.getId(), assessment.getId());
                continue;
            }

            Notification notification = Notification.builder()
                    .recipientAccount(owner)
                    .senderAccount(sender)
                    .title(title)
                    .message(message)
                    .type(NotificationType.SYSTEM)
                    .actionType(actionType)
                    .companyProfileId(companyProfileId)
                    .entityId(entityId)
                    .entityType("RELATIONSHIP_ASSESSMENT")
                    .isRead(false)
                    .isDeleted(false)
                    .build();

            notification = notificationRepository.save(notification);

            Notification savedNotification = notification;
            Account savedRecipient = owner;
            String pushMessage = message;

            runAfterCommit(() -> pushToUser(
                    savedRecipient.getId(),
                    title,
                    pushMessage,
                    java.util.Map.of(
                            "type", actionType,
                            "notificationId", String.valueOf(savedNotification.getId()),
                            "companyProfileId", companyProfileId,
                            "entityId", entityId
                    )));
        }
    }

    @Transactional
    public void notifyRelationshipAssessmentOwnerAdjusted(CompanyRelationshipAssessment assessment, CompanyRelationshipAssessment sourceAssessment, Long actorId) {
        if (assessment == null) {
            return;
        }

        Long managerId = sourceAssessment != null && sourceAssessment.getManagerAccountId() != null
                ? sourceAssessment.getManagerAccountId()
                : assessment.getManagerAccountId();

        notifyRelationshipAssessmentOwnerAdjusted(assessment, sourceAssessment, managerId, actorId);
    }

    @Transactional
    public void notifyRelationshipAssessmentOwnerAdjusted(CompanyRelationshipAssessment assessment, Long managerId, Long actorId) {
        notifyRelationshipAssessmentOwnerAdjusted(assessment, null, managerId, actorId);
    }

    @Transactional
    public void notifyRelationshipAssessmentOwnerAdjusted(CompanyRelationshipAssessment assessment, CompanyRelationshipAssessment sourceAssessment, Long managerId, Long actorId) {
        if (assessment == null || managerId == null) {
            log.warn("Cannot find source manager account for Owner Adjustment assessment={}", assessment != null ? assessment.getId() : null);
            return;
        }

        if (actorId != null && managerId.equals(actorId)) {
            return;
        }

        Account recipient = accountRepository.findById(managerId).orElse(null);
        if (recipient == null || Boolean.FALSE.equals(recipient.getIsActive())) {
            log.warn("Manager account {} not found or inactive for Owner Adjustment assessment={}", managerId, assessment.getId());
            return;
        }

        String entityId = String.valueOf(assessment.getId());
        String companyProfileId = assessment.getCompanyProfileId();

        if (notificationRepository.existsLifecycleNotification(recipient.getId(), "RELATIONSHIP_ASSESSMENT_OWNER_ADJUSTED", entityId, companyProfileId)) {
            log.debug("Notification already exists for manager={}, assessment={}", recipient.getId(), assessment.getId());
            return;
        }

        Account sender = actorId != null ? accountRepository.findById(actorId).orElse(null) : null;
        String companyName = resolveCompanyName(companyProfileId);
        String title = "Business Owner đã điều chỉnh đánh giá";

        Integer oldScore = sourceAssessment != null 
                ? (sourceAssessment.getOwnerFinalTotalScore() != null ? sourceAssessment.getOwnerFinalTotalScore() : sourceAssessment.getManagerTotalScore())
                : null;
        String oldRank = sourceAssessment != null 
                ? (StringUtils.hasText(sourceAssessment.getOwnerFinalRank()) ? sourceAssessment.getOwnerFinalRank() : sourceAssessment.getManagerRank())
                : null;

        Integer newScore = assessment.getOwnerFinalTotalScore() != null 
                ? assessment.getOwnerFinalTotalScore() 
                : assessment.getManagerTotalScore();
        String newRank = StringUtils.hasText(assessment.getOwnerFinalRank()) 
                ? assessment.getOwnerFinalRank() 
                : assessment.getManagerRank();

        String message;
        if (oldScore != null && StringUtils.hasText(oldRank) && newScore != null && StringUtils.hasText(newRank)) {
            message = String.format("%s · %d/100 · Rank %s → %d/100 · Rank %s", companyName, oldScore, oldRank, newScore, newRank);
        } else if (newScore != null && StringUtils.hasText(newRank)) {
            message = String.format("%s · %d/100 · Rank %s", companyName, newScore, newRank);
        } else {
            message = String.format("%s · V%d", companyName, assessment.getVersionNumber());
        }

        Notification notification = Notification.builder()
                .recipientAccount(recipient)
                .senderAccount(sender)
                .title(title)
                .message(message)
                .type(NotificationType.SYSTEM)
                .actionType("RELATIONSHIP_ASSESSMENT_OWNER_ADJUSTED")
                .companyProfileId(companyProfileId)
                .entityId(entityId)
                .entityType("RELATIONSHIP_ASSESSMENT")
                .isRead(false)
                .isDeleted(false)
                .build();

        notification = notificationRepository.save(notification);

        Notification savedNotification = notification;
        Account savedRecipient = recipient;
        String pushMessage = message;

        runAfterCommit(() -> pushToUser(
                savedRecipient.getId(),
                title,
                pushMessage,
                java.util.Map.of(
                        "type", "RELATIONSHIP_ASSESSMENT_OWNER_ADJUSTED",
                        "notificationId", String.valueOf(savedNotification.getId()),
                        "companyProfileId", companyProfileId,
                        "entityId", entityId
                )));
    }

    private Notification createSystemNotification(Account recipient, Account sender, String title, String message, NotificationType type) {
        return createSystemNotification(recipient, sender, title, message, type, null, null, null, null);
    }

    private Notification createSystemNotification(
            Account recipient, 
            Account sender, 
            String title, 
            String message, 
            NotificationType type,
            Long projectId,
            Long taskId,
            Long submissionId,
            String actionType) {
        
        Notification notification = Notification.builder()
                .recipientAccount(recipient)
                .senderAccount(sender)
                .title(title)
                .message(message)
                .type(type)
                .projectId(projectId)
                .taskId(taskId)
                .submissionId(submissionId)
                .actionType(actionType)
                .isRead(false)
                .isDeleted(false)
                .build();

        return notificationRepository.save(notification);
    }

    private void pushToUser(Long recipientId, String title, String body, java.util.Map<String, String> data) {
        // FCM device push removed; in-app notifications only
    }

    private void runAfterCommit(Runnable runnable) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runnable.run();
                }
            });
            return;
        }
        runnable.run();
    }

    private Notification getNotificationWithAccessCheck(Long notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found"));

        UserDetailsImpl currentUser = getCurrentUser();
        if (currentUser == null) throw new AccessDeniedException("Unauthorized");

        boolean isAdmin = hasRole(currentUser, SystemRole.SYSTEM_ADMIN);
        boolean isOwner = notification.getRecipientAccount().getId().equals(currentUser.getId());

        if (!isAdmin && !isOwner) {
            throw new AccessDeniedException("Access denied to this notification");
        }

        return notification;
    }

    private NotificationResponse toResponse(Notification notification) {
        return NotificationResponse.builder()
                .id(notification.getId())
                .recipientUserId(notification.getRecipientAccount() != null ? notification.getRecipientAccount().getId() : null)
                .senderUserId(notification.getSenderAccount() != null ? notification.getSenderAccount().getId() : null)
                .title(notification.getTitle())
                .message(notification.getMessage())
                .type(notification.getType())
                .projectId(notification.getProjectId())
                .taskId(notification.getTaskId())
                .submissionId(notification.getSubmissionId())
                .actionType(notification.getActionType())
                .documentId(notification.getDocumentId())
                .rejectReason(notification.getRejectReason())
                .companyProfileId(notification.getCompanyProfileId())
                .entityId(notification.getEntityId())
                .entityType(notification.getEntityType())
                .isRead(notification.getIsRead())
                .readAt(notification.getReadAt())
                .createdAt(notification.getCreatedAt())
                .build();
    }

    private UserDetailsImpl getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserDetailsImpl) {
            return (UserDetailsImpl) auth.getPrincipal();
        }
        return null;
    }

    private boolean hasRole(UserDetailsImpl user, SystemRole role) {
        String roleName = "ROLE_" + role.name();
        return user.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(roleName));
    }
}
