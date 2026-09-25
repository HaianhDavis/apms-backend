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
public class MutualCommitment {
    private String id;
    private String party;
    private String commitment;
    private Integer sourcePage;
    private String evidence;
    private Double confidence;
    private ContractFieldQualityStatus qualityStatus;
    private ContractFieldVerificationStatus verificationStatus;
    private ContractFieldInputMethod inputMethod;
}
