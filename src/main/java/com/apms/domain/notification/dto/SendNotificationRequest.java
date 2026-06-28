package com.apms.domain.notification.dto;

import com.apms.common.enums.NotificationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SendNotificationRequest {
    @NotNull
    private Long recipientUserId;
    @NotBlank
    private String title;
    @NotBlank
    private String message;
    @NotNull
    private NotificationType type;
}
