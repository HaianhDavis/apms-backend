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
public class AiPartnershipAgreementCandidate {
    private AiContractFieldCandidate partnershipScope;
    @Builder.Default
    private List<AiPartnerRoleCandidate> partnerRoles = new ArrayList<>();
    @Builder.Default
    private List<AiMutualCommitmentCandidate> mutualCommitments = new ArrayList<>();
    private AiContractFieldCandidate benefitSharing;
    private AiContractFieldCandidate salesOrMarketRights;
    private AiExclusivityCandidate exclusivity;
    @Builder.Default
    private List<AiPerformanceRequirementCandidate> performanceRequirements = new ArrayList<>();
    private AiContractFieldCandidate relationshipGovernance;
    @Builder.Default
    private List<AiContractFieldCandidate> terminationConditions = new ArrayList<>();
}
