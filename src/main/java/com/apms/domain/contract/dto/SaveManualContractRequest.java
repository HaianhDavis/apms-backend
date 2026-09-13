package com.apms.domain.contract.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SaveManualContractRequest {
    private String title;
    private LocalDate documentDate;
    private String contractNumber;
    private LocalDate signingDate;
    private LocalDate effectiveDate;
    private LocalDate expiryDate;
    private String term;
    private BigDecimal contractValueAmount;
    private String contractValueCurrency;
    private String rawContractValueText;
    private String governingLaw;
    private String purpose;
    private List<ManualContractPartyDto> parties;
}
