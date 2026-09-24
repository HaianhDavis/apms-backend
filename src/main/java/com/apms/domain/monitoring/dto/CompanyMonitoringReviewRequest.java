package com.apms.domain.monitoring.dto;

import com.apms.common.enums.MonitoringReviewResult;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CompanyMonitoringReviewRequest {

    @NotNull
    private MonitoringReviewResult result;

    private String updateProposalId;

    private String note;
}
