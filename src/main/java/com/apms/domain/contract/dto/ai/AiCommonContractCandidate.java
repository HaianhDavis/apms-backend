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
public class AiCommonContractCandidate {
    private AiContractFieldCandidate contractTitle;
    private AiContractFieldCandidate contractNumber;
    private AiContractFieldCandidate signingDate;
    private AiContractFieldCandidate effectiveDate;
    private AiContractFieldCandidate expiryDate;
    private AiContractFieldCandidate term;
    @Builder.Default
    private List<AiContractPartyCandidate> parties = new ArrayList<>();
    private AiContractFieldCandidate purpose;
    private AiContractValueCandidate contractValue;
    private AiContractFieldCandidate governingLaw;
    private AiContractFieldCandidate terminationClauseSummary;
}
