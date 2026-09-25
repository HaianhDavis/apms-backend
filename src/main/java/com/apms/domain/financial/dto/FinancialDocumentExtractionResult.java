package com.apms.domain.financial.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinancialDocumentExtractionResult {
    @JsonProperty("documentContext")
    private AiFinancialDocumentContextCandidate documentContextCandidate;
    
    @JsonProperty("metrics")
    private List<AiFinancialMetricCandidate> metricCandidates;
}
