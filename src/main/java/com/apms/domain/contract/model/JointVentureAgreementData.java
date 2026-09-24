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
public class JointVentureAgreementData {
    private ExtractedContractField<String> jointVentureName;
    private ExtractedContractField<String> jointVenturePurpose;

    @Builder.Default
    private List<CapitalContribution> capitalContributions = new ArrayList<>();

    @Builder.Default
    private List<OwnershipPercentage> ownershipPercentages = new ArrayList<>();

    private ExtractedContractField<String> governanceStructure;

    @Builder.Default
    private List<VotingRight> votingRights = new ArrayList<>();

    @Builder.Default
    private List<ExtractedContractField<String>> decisionMakingRules = new ArrayList<>();

    @Builder.Default
    private List<DistributionShare> profitDistribution = new ArrayList<>();

    @Builder.Default
    private List<DistributionShare> lossSharing = new ArrayList<>();

    @Builder.Default
    private List<ManagementAppointment> managementAppointments = new ArrayList<>();

    @Builder.Default
    private List<ExtractedContractField<String>> exitConditions = new ArrayList<>();

    @Builder.Default
    private List<ExtractedContractField<String>> transferRestrictions = new ArrayList<>();
}
