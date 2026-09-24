package com.apms.domain.notification.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchMarkReadRequest {
    @NotEmpty(message = "Notification IDs cannot be empty")
    private List<Long> notificationIds;
}
