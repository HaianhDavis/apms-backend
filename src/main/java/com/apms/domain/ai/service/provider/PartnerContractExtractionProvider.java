package com.apms.domain.ai.service.provider;

import com.apms.domain.contract.dto.PartnerContractExtractionOutput;

public interface PartnerContractExtractionProvider {
    /**
     * Extracts contract metadata and clauses from a bounded text chunk.
     * @param sourceText The bounded text segment to analyze.
     * @return The structured output ensuring strict limits and schema validation.
     */
    PartnerContractExtractionOutput extractContract(String sourceText);
}
