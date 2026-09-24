package com.apms.domain.monitoring.dto;

import com.apms.common.enums.MonitoringReviewResult;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class CompanyMonitoringReviewResponse {
    private Long id;
    private Long monitoringAssignmentId;
    private String companyProfileId;
    private String companyName;

    private Long reviewedById;
    private String reviewedByName;
    private String reviewedByEmail;

    private LocalDateTime reviewedAt;
    private MonitoringReviewResult result;
    private String updateProposalId;
    private String proposalStatus;
    private String note;
}
