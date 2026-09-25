package com.apms.domain.contract.dto.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiContributionRatioCandidate {
    private String party;
    private String ratioPercentage;
    private Integer sourcePage;
    private String evidence;
    private Double confidence;
}
