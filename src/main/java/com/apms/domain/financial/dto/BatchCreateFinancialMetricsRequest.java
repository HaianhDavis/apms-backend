package com.apms.domain.financial.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchCreateFinancialMetricsRequest {
    @Builder.Default
    private List<CreateFinancialMetricRequest> metrics = new ArrayList<>();
}
