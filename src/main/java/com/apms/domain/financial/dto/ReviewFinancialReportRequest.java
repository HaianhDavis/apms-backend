package com.apms.domain.financial.dto;

import com.apms.domain.financial.FinancialReportReviewStatus;
import lombok.Data;
import jakarta.validation.constraints.NotNull;

@Data
public class ReviewFinancialReportRequest {
    @NotNull
    private FinancialReportReviewStatus status;
    private String reason;
}
