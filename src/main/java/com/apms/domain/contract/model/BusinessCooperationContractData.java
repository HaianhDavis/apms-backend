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
public class BusinessCooperationContractData {
    private ExtractedContractField<String> businessScope;

    @Builder.Default
    private List<BccContribution> contributions = new ArrayList<>();

    @Builder.Default
    private List<ContributionRatio> contributionRatios = new ArrayList<>();

    @Builder.Default
    private List<SharingArrangement> revenueSharing = new ArrayList<>();

    @Builder.Default
    private List<SharingArrangement> profitSharing = new ArrayList<>();

    @Builder.Default
    private List<SharingArrangement> costSharing = new ArrayList<>();

    @Builder.Default
    private List<SharingArrangement> lossSharing = new ArrayList<>();

    @Builder.Default
    private List<PartyRightsAndObligations> rightsAndObligations = new ArrayList<>();

    private ExtractedContractField<String> managementMechanism;
    private ExtractedContractField<String> financialManagement;
    private ExtractedContractField<String> assetOwnership;
    private ExtractedContractField<String> terminationSettlement;
}
