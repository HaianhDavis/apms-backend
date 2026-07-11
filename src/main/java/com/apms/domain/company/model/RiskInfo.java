package com.apms.domain.company.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskInfo {
    private String legalRisk;
    private String financialRisk;
    private String reputationRisk;
    private String securityRisk;
    private String conflictOfInterestRisk;
    private String supplyInterruptionRisk;
    private String dependencyRisk;
    private String overallRiskLevel;
}
