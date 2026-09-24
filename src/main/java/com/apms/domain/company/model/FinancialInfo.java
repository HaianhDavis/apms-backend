package com.apms.domain.company.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinancialInfo {
    private BigDecimal revenue;
    private String revenueCurrency;
    private BigDecimal revenueGrowth;
    private BigDecimal debtRatio;
    private BigDecimal profitMargin;
    private String fundingStage;
    private String profitability;
}
