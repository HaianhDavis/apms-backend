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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final AccountRepository accountRepository;
    private final AuditLogService auditLogService;

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
