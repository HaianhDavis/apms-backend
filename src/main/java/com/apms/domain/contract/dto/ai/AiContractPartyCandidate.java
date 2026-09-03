package com.apms.domain.contract.dto.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiContractPartyCandidate {
    private String legalName;
    private String taxCode;
    private String address;
    private String representative;
    private String role;
    private Integer sourcePage;
    private String evidence;
    private Double confidence;
}
