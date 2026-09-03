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
public class AiBusinessCooperationContractCandidate {
    private AiContractFieldCandidate businessScope;
    @Builder.Default
    private List<AiBccContributionCandidate> contributions = new ArrayList<>();
    @Builder.Default
    private List<AiContributionRatioCandidate> contributionRatios = new ArrayList<>();
    @Builder.Default
    private List<AiSharingArrangementCandidate> revenueSharing = new ArrayList<>();
    @Builder.Default
    private List<AiSharingArrangementCandidate> profitSharing = new ArrayList<>();
    @Builder.Default
    private List<AiSharingArrangementCandidate> costSharing = new ArrayList<>();
    @Builder.Default
    private List<AiSharingArrangementCandidate> lossSharing = new ArrayList<>();
    @Builder.Default
    private List<AiPartyRightsAndObligationsCandidate> rightsAndObligations = new ArrayList<>();
    private AiContractFieldCandidate managementMechanism;
    private AiContractFieldCandidate financialManagement;
    private AiContractFieldCandidate assetOwnership;
    private AiContractFieldCandidate terminationSettlement;
}
