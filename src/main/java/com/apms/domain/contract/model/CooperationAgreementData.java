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
public class CooperationAgreementData {
    private ExtractedContractField<String> cooperationScope;

    @Builder.Default
    private List<ExtractedContractField<String>> cooperationActivities = new ArrayList<>();

    @Builder.Default
    private List<PartyResponsibility> responsibilities = new ArrayList<>();

    @Builder.Default
    private List<ResourceCommitment> resourceCommitments = new ArrayList<>();

    private ExtractedContractField<String> informationSharing;
    private ExtractedContractField<String> coordinationMechanism;

    @Builder.Default
    private List<ExtractedContractField<String>> terminationConditions = new ArrayList<>();
}
