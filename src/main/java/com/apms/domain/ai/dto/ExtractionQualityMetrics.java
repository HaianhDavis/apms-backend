package com.apms.domain.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExtractionQualityMetrics {
    private int totalFields;
    private int fieldsWithValue;
    private int fieldsWithEvidence;
    private int passedFields;
    private int warningFields;
    private int failedFields;
    private Double averageConfidence;
    private Double evidenceCoverageRate;
    private Double completenessRate;
    private int hallucinationRiskCount;
}
