package com.apms.domain.audit.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class AuditLogResponse {
    private Long id;
    private Long actorAccountId;
    private String actorEmail;
    private String action;
    private String actionLabel;
    private String entityType;
    private String entityId;
    private String entityName;
    private String detail;
    private LocalDateTime timestamp;
}
