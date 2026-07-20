package com.apms.domain.contract.dto;

import com.apms.domain.contract.entity.PartnerContractExtractionDraft.ClauseCandidate;
import com.apms.domain.contract.entity.PartnerContractExtractionDraft.ContractExtractionFieldResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartnerContractExtractionOutput {
    private List<ContractExtractionFieldResult> metadataFields;
    private List<ClauseCandidate> clauseCandidates;
    private List<String> warnings;
    private List<String> missingData;
    private List<String> ambiguities;

    // Extracted raw JSON for audit
    private String rawAiOutput;
}
