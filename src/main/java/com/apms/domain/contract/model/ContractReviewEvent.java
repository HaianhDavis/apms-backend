package com.apms.domain.contract.model;

import com.apms.domain.contract.enums.ContractEntryReviewStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractReviewEvent {
    private String id;
    private Long submissionId;
    private String contractEntryId;
    private ContractEntryReviewStatus decision;
    private String reason;
    private Long reviewedBy;
    private String reviewedByName;
    private LocalDateTime reviewedAt;
}
