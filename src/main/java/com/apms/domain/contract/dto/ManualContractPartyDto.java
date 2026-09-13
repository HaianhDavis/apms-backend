package com.apms.domain.contract.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ManualContractPartyDto {
    private String id;
    private String legalName;
    private String role;
    private String taxCode;
    private String representative;
    private String address;
    private Boolean isTargetCompany;
}
