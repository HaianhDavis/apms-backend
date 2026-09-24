package com.apms.domain.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class MergeExtractionsIntoProposalRequest {
    @NotBlank
    private String companyProfileId;
    @NotEmpty
    private List<String> extractionIds;
    private String changeSummary;
}
