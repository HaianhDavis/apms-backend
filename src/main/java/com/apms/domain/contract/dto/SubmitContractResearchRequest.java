package com.apms.domain.contract.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmitContractResearchRequest {
    @NotEmpty(message = "At least one contract must be selected for submission")
    private List<String> contractEntryIds;

    private String note;
}
