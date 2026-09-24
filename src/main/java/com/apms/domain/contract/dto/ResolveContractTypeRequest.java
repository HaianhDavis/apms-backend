package com.apms.domain.contract.dto;

import com.apms.domain.contract.enums.ContractType;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResolveContractTypeRequest {
    @NotNull(message = "Confirmed contract type is required")
    private ContractType confirmedContractType;
}
