package com.apms.domain.contract.dto;

import lombok.Data;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

@Data
public class SubmitPartnerContractCollectionRequest {
    @NotEmpty(message = "At least one contract draft must be selected")
    private List<String> contractDraftIds;
    private String note;
}
