package com.apms.domain.admin.dto;

import com.apms.common.enums.AuditAction;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class AuditLogResponse {
    private Long id;
    private Long actorAccountId;
    private String actorEmail;
    private Long projectId;
    private AuditAction action;
    private String entityType;
    private String entityId;
    private String detail;
    private LocalDateTime timestamp;
}

