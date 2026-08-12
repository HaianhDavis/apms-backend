package com.apms.domain.notification.dto;

import com.apms.common.enums.NotificationType;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class NotificationResponse {
    private Long id;
    private Long recipientUserId;
    private Long senderUserId;
    private String title;
    private String message;
    private NotificationType type;
    private Long projectId;
    private Long taskId;
    private Long submissionId;
    private String actionType;
    private String documentId;
    private String rejectReason;
    private Boolean isRead;
    private LocalDateTime readAt;
    private LocalDateTime createdAt;
}
