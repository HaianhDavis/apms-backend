package com.apms.domain.contract.model;

import com.apms.domain.contract.enums.ContractFieldInputMethod;
import com.apms.domain.contract.enums.ContractFieldQualityStatus;
import com.apms.domain.contract.enums.ContractFieldVerificationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PerformanceRequirement {
    private String id;
    private String requirement;
    private String target;
    private Integer sourcePage;
    private String evidence;
    private Double confidence;
    private ContractFieldQualityStatus qualityStatus;
    private ContractFieldVerificationStatus verificationStatus;
    private ContractFieldInputMethod inputMethod;
}
