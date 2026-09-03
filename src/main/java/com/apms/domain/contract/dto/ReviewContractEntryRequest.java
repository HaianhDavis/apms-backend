package com.apms.domain.contract.dto;

import com.apms.domain.contract.enums.ContractEntryReviewStatus;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewContractEntryRequest {
    @NotNull(message = "Review status is required")
    private ContractEntryReviewStatus status;

    private String reason;
}
