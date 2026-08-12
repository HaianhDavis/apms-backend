package com.apms.domain.notification.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.NotificationType;
import com.apms.common.enums.SystemRole;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.notification.FcmDeviceToken;
import com.apms.domain.notification.Notification;
import com.apms.domain.notification.dto.NotificationResponse;
import com.apms.domain.notification.dto.RegisterFcmTokenRequest;
import com.apms.domain.notification.dto.SendNotificationRequest;
import com.apms.domain.notification.repository.sql.FcmDeviceTokenRepository;
import com.apms.domain.notification.repository.sql.NotificationRepository;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import com.apms.security.UserDetailsImpl;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
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
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final FcmDeviceTokenRepository fcmDeviceTokenRepository;
    private final AccountRepository accountRepository;
    private final AuditLogService auditLogService;
    private final ObjectProvider<FirebaseMessaging> firebaseMessagingProvider;

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
    public void registerFcmToken(RegisterFcmTokenRequest request) {
        UserDetailsImpl currentUser = getCurrentUser();
        if (currentUser == null) throw new AccessDeniedException("Unauthorized");

        Account account = accountRepository.findById(currentUser.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));

        String token = request.getToken().trim();
        FcmDeviceToken deviceToken = fcmDeviceTokenRepository.findByToken(token)
                .orElseGet(() -> FcmDeviceToken.builder().token(token).build());

        deviceToken.setAccount(account);
        deviceToken.setDeviceType(StringUtils.hasText(request.getDeviceType()) ? request.getDeviceType().trim() : "WEB");
        deviceToken.setIsActive(true);
        deviceToken.setLastSeenAt(LocalDateTime.now());
        fcmDeviceTokenRepository.save(deviceToken);
    }

    @Transactional
    public void unregisterFcmToken(String token) {
        UserDetailsImpl currentUser = getCurrentUser();
        if (currentUser == null) throw new AccessDeniedException("Unauthorized");
        if (!StringUtils.hasText(token)) return;

        fcmDeviceTokenRepository.findByToken(token.trim()).ifPresent(deviceToken -> {
            if (deviceToken.getAccount() != null && deviceToken.getAccount().getId().equals(currentUser.getId())) {
                deviceToken.setIsActive(false);
                fcmDeviceTokenRepository.save(deviceToken);
            }
        });
    }

    @Transactional
    public void notifyTaskAssigned(ProjectTask task, Account recipient, Account sender) {
        if (task == null || recipient == null) {
            return;
        }

        String projectName = task.getProject() != null ? task.getProject().getProjectName() : "Project";
        String title = "You have a new task";
        String message = String.format("%s - %s", projectName, task.getTitle());
        Notification notification = createSystemNotification(recipient, sender, title, message, NotificationType.TASK);

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
        Notification notification = createSystemNotification(recipient, sender, title, message, NotificationType.SYSTEM);

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

    private Notification createSystemNotification(Account recipient, Account sender, String title, String message, NotificationType type) {
        Notification notification = Notification.builder()
                .recipientAccount(recipient)
                .senderAccount(sender)
                .title(title)
                .message(message)
                .type(type)
                .isRead(false)
                .isDeleted(false)
                .build();

        return notificationRepository.save(notification);
    }

    private void pushToUser(Long recipientId, String title, String body, java.util.Map<String, String> data) {
        FirebaseMessaging firebaseMessaging = firebaseMessagingProvider.getIfAvailable();
        if (firebaseMessaging == null) {
            log.debug("Firebase is not configured; skipping FCM push for user={}", recipientId);
            return;
        }

        List<FcmDeviceToken> tokens = fcmDeviceTokenRepository.findByAccount_IdAndIsActiveTrue(recipientId);
        if (tokens.isEmpty()) {
            log.debug("No active FCM tokens for user={}", recipientId);
            return;
        }

        for (FcmDeviceToken token : tokens) {
            try {
                Message message = Message.builder()
                        .setToken(token.getToken())
                        .setNotification(com.google.firebase.messaging.Notification.builder()
                                .setTitle(title)
                                .setBody(body)
                                .build())
                        .putAllData(data)
                        .build();
                firebaseMessaging.send(message);
            } catch (Exception ex) {
                log.warn("Failed to send FCM notification to user={}, tokenId={}: {}", recipientId, token.getId(), ex.getMessage());
            }
        }
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
