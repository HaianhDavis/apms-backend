package com.apms.domain.contract.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartnerContractSummaryResponse {
    private int totalContracts;
    private int activeContracts;
    private int upcomingContracts;
    private int expiringSoonContracts;
    private int expiredContracts;
    private int terminatedContracts;
    private LocalDate nearestExpiryDate;
    private Map<String, BigDecimal> totalApprovedValueByCurrency;
}
