package com.apms.domain.contract.model;

import com.apms.domain.contract.enums.ContractFieldInputMethod;
import com.apms.domain.contract.enums.ContractFieldQualityStatus;
import com.apms.domain.contract.enums.ContractFieldVerificationStatus;
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
public class PartyRightsAndObligations {
    private String id;
    private String party;

    @Builder.Default
    private List<String> rights = new ArrayList<>();

    @Builder.Default
    private List<String> obligations = new ArrayList<>();

    private Integer sourcePage;
    private String evidence;
    private Double confidence;
    private ContractFieldQualityStatus qualityStatus;
    private ContractFieldVerificationStatus verificationStatus;
    private ContractFieldInputMethod inputMethod;
}
