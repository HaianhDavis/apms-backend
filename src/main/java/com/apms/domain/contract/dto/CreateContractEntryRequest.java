package com.apms.domain.contract.dto;

import com.apms.domain.contract.enums.ContractTypeSelection;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateContractEntryRequest {
    @NotBlank(message = "Title is required")
    private String title;

    private LocalDate documentDate;

    @NotBlank(message = "Document ID is required")
    private String documentId;

    private ContractTypeSelection declaredContractType;
}
