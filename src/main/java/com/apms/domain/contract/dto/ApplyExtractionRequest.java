package com.apms.domain.contract.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApplyExtractionRequest {
    private Integer expectedContractOptimisticVersion;
    private Integer expectedCurrentApprovedVersion;
    private Integer expectedNextApprovalVersion;
    private String expectedSourceDocumentHash;
    private List<String> confirmedOverwriteFieldKeys;
}
