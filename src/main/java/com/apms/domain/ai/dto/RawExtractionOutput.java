package com.apms.domain.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RawExtractionOutput {
    private ExtractedCompanyData extractedData;
    private Map<String, ExtractionFieldResult> fieldResults;
    private String rawAiOutputString;
}
