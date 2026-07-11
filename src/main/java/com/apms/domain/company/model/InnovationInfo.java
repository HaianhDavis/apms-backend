package com.apms.domain.company.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InnovationInfo {
    private Integer patents;
    private BigDecimal rdInvestmentPercent;
    private List<String> techStack;
    private Integer techMaturityLevel;
    private BigDecimal productInnovationRate;
    private List<String> technologyCapabilities;
}
