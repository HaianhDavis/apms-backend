package com.apms.domain.contract.dto;

import com.apms.domain.contract.enums.ContractLifecycleStatus;
import lombok.Data;

@Data
public class UpdateContractLifecycleRequest {
    private ContractLifecycleStatus lifecycleStatus;
}
