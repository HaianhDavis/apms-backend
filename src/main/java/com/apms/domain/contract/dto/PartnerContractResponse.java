package com.apms.domain.contract.dto;

import com.apms.domain.contract.enums.ContractLifecycleStatus;
import com.apms.domain.contract.enums.ContractReviewStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
public class PartnerContractResponse {
    private Long id;
    private String referenceCompanyId;
    private String partnerCompanyId;
    private Long sourceProjectId;
    private Long sourceTaskId;
    private String rawDocumentId;
    private String contractNumber;
    private String contractTitle;
    private String contractType;
    private ContractReviewStatus reviewStatus;
    private ContractLifecycleStatus lifecycleStatus;
    private LocalDate signedDate;
    private LocalDate effectiveDate;
    private LocalDate expiryDate;
    private String currency;
    private BigDecimal totalContractValue;
    private Integer currentVersion;
    private Long createdByAccountId;
    private LocalDateTime createdAt;
    private Long updatedByAccountId;
    private LocalDateTime updatedAt;
    private Long approvedByAccountId;
    private LocalDateTime approvedAt;
    private Integer version;
}
