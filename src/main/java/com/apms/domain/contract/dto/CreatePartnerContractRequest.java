package com.apms.domain.contract.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class CreatePartnerContractRequest {
    private Long sourceTaskId;
    private String rawDocumentId;
    private String contractNumber;
    private String contractTitle;
    private String contractType;
    private LocalDate signedDate;
    private LocalDate effectiveDate;
    private LocalDate expiryDate;
    private String currency;
    private BigDecimal totalContractValue;
}
