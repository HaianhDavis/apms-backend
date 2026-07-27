package com.apms.domain.rolemetric.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class CreateRoleMetricRequest {
    private Long taskId;
    private String metricKey;
    private LocalDate measurementDate;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private BigDecimal targetNumericValue;
    private BigDecimal actualNumericValue;
    private Boolean targetBooleanValue;
    private Boolean actualBooleanValue;
    private String unitCode;
    private String currencyCode;
}
