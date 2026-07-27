package com.apms.domain.rolemetric.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class UpdateRoleMetricRequest {
    // Identity fields can only be updated if the metric has never been approved.
    private LocalDate measurementDate;
    private LocalDate periodStart;
    private LocalDate periodEnd;

    // Value fields
    private BigDecimal targetNumericValue;
    private BigDecimal actualNumericValue;
    private Boolean targetBooleanValue;
    private Boolean actualBooleanValue;
    private String unitCode;
    private String currencyCode;
}
