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
public class AiCooperationAgreementCandidate {
    private AiContractFieldCandidate cooperationScope;
    @Builder.Default
    private List<AiContractFieldCandidate> cooperationActivities = new ArrayList<>();
    @Builder.Default
    private List<AiPartyResponsibilityCandidate> responsibilities = new ArrayList<>();
    @Builder.Default
    private List<AiResourceCommitmentCandidate> resourceCommitments = new ArrayList<>();
    private AiContractFieldCandidate informationSharing;
    private AiContractFieldCandidate coordinationMechanism;
    @Builder.Default
    private List<AiContractFieldCandidate> terminationConditions = new ArrayList<>();
}
