package com.apms.domain.project.dto;

import com.apms.common.enums.FieldApprovalStatus;
import lombok.Data;

@Data
public class FieldReviewDecisionItem {
    private String fieldPath;
    private FieldApprovalStatus decision; // Expected: APPROVED or REVISION_REQUIRED
    private String comment;
}
