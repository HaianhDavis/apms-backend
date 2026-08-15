package com.apms.domain.monitoring.dto;

import com.apms.common.enums.RelationshipType;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class RelationshipHistoryResponse {
    private Long id;
    private String companyProfileId;
    private RelationshipType oldRelationshipType;
    private RelationshipType newRelationshipType;
    private String reason;
    private LocalDateTime effectiveAt;
    private LocalDateTime changedAt;
    private Long proposedByAccountId;
    private String proposedByAccountName;
    private Long approvedByAccountId;
    private String approvedByAccountName;
}
