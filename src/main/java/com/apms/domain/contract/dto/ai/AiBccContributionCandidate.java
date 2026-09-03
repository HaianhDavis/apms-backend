package com.apms.domain.contract.dto.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiBccContributionCandidate {
    private String party;
    private String amount;
    private String currency;
    private String contributionType;
    private String description;
    private Integer sourcePage;
    private String evidence;
    private Double confidence;
}
