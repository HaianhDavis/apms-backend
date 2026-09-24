package com.apms.domain.ai.service.provider;

import com.apms.domain.ai.dto.RawExtractionOutput;

public interface ExtractionProvider {
    /**
     * Extracts structured company data from the provided text.
     * @param sourceText The input text to analyze.
     * @return The extracted company data object with both legacy and QA field results.
     */
    RawExtractionOutput extract(String sourceText);
}
