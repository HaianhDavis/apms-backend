package com.apms.domain.contract.dto.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiContractClassificationCandidate {
    private String detectedContractType;
    private String contractTitle;
    private String language;
    @Builder.Default
    private List<String> candidateParties = new ArrayList<>();
    private Integer sourcePage;
    private String evidence;
    private Double confidence;
}
