package com.apms.domain.contract.dto;

import com.apms.domain.contract.enums.ContractExtractionReviewDecision;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewExtractionFieldRequest {
    private ContractExtractionReviewDecision reviewDecision;
    private Object reviewedValue;
    private String reviewComment;
}
