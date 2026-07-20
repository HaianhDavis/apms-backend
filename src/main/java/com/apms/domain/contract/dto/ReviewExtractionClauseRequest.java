package com.apms.domain.contract.dto;

import com.apms.domain.contract.enums.ContractExtractionReviewDecision;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewExtractionClauseRequest {
    private ContractExtractionReviewDecision reviewDecision;
    private String reviewComment;

    // Editable fields below (only updated if decision is EDIT)
    private String clauseTitle;
    private String clauseType;
    private ContractClauseTerms normalizedTerms;

    private LocalDate effectiveDate;
    private LocalDate expiryDate;
    private Integer noticePeriodDays;

    private String targetMetricKey;
    private String targetValue;
    private String targetUnit;
    private String comparator;
    private String measurementPeriod;

    private BigDecimal penaltyValue;
    private String penaltyCurrency;
    private String penaltyDescription;

    private List<String> reviewedFields;
}
