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
public class AiJointVentureAgreementCandidate {
    private AiContractFieldCandidate jointVentureName;
    private AiContractFieldCandidate jointVenturePurpose;
    @Builder.Default
    private List<AiCapitalContributionCandidate> capitalContributions = new ArrayList<>();
    @Builder.Default
    private List<AiOwnershipPercentageCandidate> ownershipPercentages = new ArrayList<>();
    private AiContractFieldCandidate governanceStructure;
    @Builder.Default
    private List<AiVotingRightCandidate> votingRights = new ArrayList<>();
    @Builder.Default
    private List<AiContractFieldCandidate> decisionMakingRules = new ArrayList<>();
    @Builder.Default
    private List<AiDistributionShareCandidate> profitDistribution = new ArrayList<>();
    @Builder.Default
    private List<AiDistributionShareCandidate> lossSharing = new ArrayList<>();
    @Builder.Default
    private List<AiManagementAppointmentCandidate> managementAppointments = new ArrayList<>();
    @Builder.Default
    private List<AiContractFieldCandidate> exitConditions = new ArrayList<>();
    @Builder.Default
    private List<AiContractFieldCandidate> transferRestrictions = new ArrayList<>();
}
