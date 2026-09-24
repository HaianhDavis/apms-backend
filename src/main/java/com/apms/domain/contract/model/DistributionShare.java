package com.apms.domain.contract.model;

import com.apms.domain.contract.enums.ContractFieldInputMethod;
import com.apms.domain.contract.enums.ContractFieldQualityStatus;
import com.apms.domain.contract.enums.ContractFieldVerificationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DistributionShare {
    private String id;
    private String party;
    private BigDecimal percentage;
    private String description;
    private Integer sourcePage;
    private String evidence;
    private Double confidence;
    private ContractFieldQualityStatus qualityStatus;
    private ContractFieldVerificationStatus verificationStatus;
    private ContractFieldInputMethod inputMethod;
}
