package com.apms.domain.rolemetric.dto;

import com.apms.domain.rolemetric.enums.RoleMetricReviewDecision;
import lombok.Data;

@Data
public class ReviewRoleMetricRequest {
    private RoleMetricReviewDecision decision;
    private String comment;
}
