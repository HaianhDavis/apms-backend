package com.apms.domain.profile.dto;

import com.apms.domain.contract.model.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateCompanyProfileContractRequest {

    private String id;
    private String title;
    private LocalDate documentDate;

    // Common Contract Business Fields
    private String contractNumber;
    private LocalDate signingDate;
    private LocalDate effectiveDate;
    private LocalDate expiryDate;
    private String term;
    private ContractValue contractValue;
    private String governingLaw;
    private String purpose;
    private List<ContractParty> parties;

    // Subtype payloads (view/edit existing subtype, contractType cannot be changed)
    private CooperationAgreementData cooperationAgreementData;
    private PartnershipAgreementData partnershipAgreementData;
    private JointVentureAgreementData jointVentureAgreementData;
    private BusinessCooperationContractData businessCooperationContractData;
}
