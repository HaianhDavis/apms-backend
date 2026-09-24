package com.apms.domain.ai.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class MergeExtractionsIntoCandidateRequest {
    @NotEmpty
    private List<String> extractionIds;
    private String note;
}
