package com.apms.domain.monitoring.dto;

import com.apms.common.enums.RelationshipType;
import com.apms.common.enums.RelationshipChangeStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class RelationshipChangeProposalResponse {
    private Long id;
    private String companyProfileId;
    private Long monitoringAssignmentId;
    private RelationshipType oldRelationshipType;
    private RelationshipType newRelationshipType;
    private String reason;
    private LocalDateTime effectiveAt;
    private Long proposedByAccountId;
    private String proposedByAccountName;
    private LocalDateTime proposedAt;
    private RelationshipChangeStatus status;
    private Long reviewedByAccountId;
    private String reviewedByAccountName;
    private LocalDateTime reviewedAt;
    private String rejectReason;
}
