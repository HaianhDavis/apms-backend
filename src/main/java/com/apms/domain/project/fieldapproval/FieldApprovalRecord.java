package com.apms.domain.project.fieldapproval;

import com.apms.common.enums.FieldApprovalStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FieldApprovalRecord {
    private String fieldPath;
    private FieldApprovalStatus status;
    private Integer reviewedRevision;
    private Long reviewedByAccountId;
    private LocalDateTime reviewedAt;
    private String comment;
    private String approvedValueHash;

    private Long reopenedByAccountId;
    private LocalDateTime reopenedAt;
    private String reopenReason;

    private String staleReason;
    private Object pendingValue;
    private String pendingValueHash;
    private List<String> pendingEvidenceIds;

    // Resubmission history tracking
    private FieldApprovalStatus previousStatus;
    private String previousComment;
    private Integer changedInRevision;
}
