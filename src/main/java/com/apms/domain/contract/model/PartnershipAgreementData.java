package com.apms.domain.contract.model;

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
public class PartnershipAgreementData {
    private ExtractedContractField<String> partnershipScope;

    @Builder.Default
    private List<PartnerRole> partnerRoles = new ArrayList<>();

    @Builder.Default
    private List<MutualCommitment> mutualCommitments = new ArrayList<>();

    private ExtractedContractField<String> benefitSharing;
    private ExtractedContractField<String> salesOrMarketRights;
    private ExtractedContractField<ExclusivityClause> exclusivity;

    @Builder.Default
    private List<PerformanceRequirement> performanceRequirements = new ArrayList<>();

    private ExtractedContractField<String> relationshipGovernance;

    @Builder.Default
    private List<ExtractedContractField<String>> terminationConditions = new ArrayList<>();
}
