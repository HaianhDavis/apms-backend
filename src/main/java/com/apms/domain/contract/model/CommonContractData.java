package com.apms.domain.contract.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommonContractData {
    private ExtractedContractField<String> contractTitle;
    private ExtractedContractField<String> contractNumber;
    private ExtractedContractField<LocalDate> signingDate;
    private ExtractedContractField<LocalDate> effectiveDate;
    private ExtractedContractField<LocalDate> expiryDate;
    private ExtractedContractField<String> term;

    @Builder.Default
    private List<ContractParty> parties = new ArrayList<>();

    private ExtractedContractField<String> purpose;
    private ExtractedContractField<ContractValue> contractValue;
    private ExtractedContractField<String> governingLaw;
}
