package com.apms.domain.profile.assessment.dto;

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
public class CommercialEvidenceResponse {
    private int approvedContractCount;
    private int upcomingContractCount;
    private LocalDate firstCooperationDate;
    private LocalDate latestContractDate;
    private Long relationshipDurationDays;
    private Long relationshipDurationMonths;
    private Long contractRecencyDays;
    private Long contractRecencyMonths;
    private BigDecimal totalContractValueVnd;
    private Map<String, BigDecimal> valueByCurrency;
    private String contractCurrencies;
    private String contractValueStatus; // SCORABLE, UNSCORABLE_NON_VND, NO_CONTRACTS
    private Integer contractValueScore; // null if UNSCORABLE_NON_VND
    private Integer contractCountScore;
    private Integer relationshipDurationScore;
    private Integer contractRecencyScore;
    private Integer commercialScore;
    private int scorableBase;
    private boolean normalizationApplied;
    private String scoringPolicyVersion;
    private boolean hasValidHistoricalDates;
    private String commercialSuggestionStatus; // COMPLETE, PARTIAL, UNAVAILABLE
    private Integer commercialAvailablePoints; // e.g. 50, 30
}
