package com.apms.domain.contract.dto.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiPerformanceRequirementCandidate {
    private String requirement;
    private String target;
    private Integer sourcePage;
    private String evidence;
    private Double confidence;
}
