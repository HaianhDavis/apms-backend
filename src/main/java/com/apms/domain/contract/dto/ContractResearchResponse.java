package com.apms.domain.contract.dto;

import com.apms.domain.contract.enums.ContractResearchStatus;
import com.apms.domain.contract.model.ContractEntry;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractResearchResponse {
    private String id;
    private Long taskId;
    private Long projectId;
    private String companyProfileId;
    private ContractResearchStatus status;

    @Builder.Default
    private List<ContractEntry> contracts = new ArrayList<>();

    // Active submission context
    private Long activeSubmissionId;
    private List<String> activeSubmittedContractIds;
    private Boolean canRecallSubmission;
    private String activeSubmissionStatus;
    private LocalDateTime submittedAt;

    private Long reviewedBy;
    private LocalDateTime reviewedAt;
    private String reviewReason;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
