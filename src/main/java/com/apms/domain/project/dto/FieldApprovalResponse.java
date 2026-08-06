package com.apms.domain.project.dto;

import com.apms.common.enums.FieldApprovalStatus;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class FieldApprovalResponse {
    private FieldApprovalStatus status;
    private Integer reviewedRevision;
    private String comment;
    
    private String previousComment;
    private FieldApprovalStatus previousStatus;
    private Integer changedInRevision;

    // STALE info
    private String staleReason;
    private Object pendingValue;
    private List<String> pendingEvidenceIds;
}
