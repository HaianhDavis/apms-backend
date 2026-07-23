package com.apms.domain.score.dto.draft;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CriterionReadinessResult {
    private String criterionKey;
    private com.apms.domain.score.service.PartnerDataSufficiencyEvaluator.SufficiencyStatus sufficiencyStatus;
    private List<String> missingCategories;
    private List<String> reasons;
    private List<String> evidenceReferenceIds;
}
