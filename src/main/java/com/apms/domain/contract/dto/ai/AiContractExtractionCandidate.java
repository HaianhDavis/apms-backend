package com.apms.domain.contract.dto.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiContractExtractionCandidate {
    private AiCommonContractCandidate commonData;
    private AiCooperationAgreementCandidate cooperationAgreementData;
    private AiPartnershipAgreementCandidate partnershipAgreementData;
    private AiJointVentureAgreementCandidate jointVentureAgreementData;
    private AiBusinessCooperationContractCandidate businessCooperationContractData;
}
