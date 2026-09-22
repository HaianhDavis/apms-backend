package com.apms.domain.profile.dto;

import com.apms.domain.contract.enums.ContractStatus;
import com.apms.domain.contract.enums.ContractType;
import com.apms.domain.contract.model.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanyProfileContractDto {

    private String id;
    private String companyProfileId;

    private String title;
    private ContractType contractType;
    private ContractStatus derivedContractStatus;
    private LocalDate documentDate;

    // Full contract model with preserved evidence
    private CommonContractData commonData;
    private CooperationAgreementData cooperationAgreementData;
    private PartnershipAgreementData partnershipAgreementData;
    private JointVentureAgreementData jointVentureAgreementData;
    private BusinessCooperationContractData businessCooperationContractData;

    // Provenance & Source
    private String sourceType;
    private String sourceResearchId;
    private String sourceContractEntryId;
    private String sourceDocumentId;
    private String sourceDocumentName;
    private Long projectId;
    private Long taskId;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long lastModifiedBy;

    // Backward-compatible getters for frontend consumption
    public String getDocumentId() {
        return sourceDocumentId;
    }

    public String getDocumentName() {
        return sourceDocumentName;
    }

    public ContractType getConfirmedContractType() {
        return contractType;
    }
}
