package com.apms.domain.contract.dto;

import com.apms.domain.contract.entity.PartnerContract;
import com.apms.domain.contract.entity.PartnerContractVersion;

public class PartnerContractMapper {

    public static PartnerContractResponse toResponse(PartnerContract contract) {
        if (contract == null) return null;
        return PartnerContractResponse.builder()
                .id(contract.getId())
                .referenceCompanyId(contract.getReferenceCompanyId())
                .partnerCompanyId(contract.getPartnerCompanyId())
                .sourceProjectId(contract.getSourceProjectId())
                .sourceTaskId(contract.getSourceTaskId())
                .rawDocumentId(contract.getRawDocumentId())
                .contractNumber(contract.getContractNumber())
                .contractTitle(contract.getContractTitle())
                .contractType(contract.getContractType())
                .reviewStatus(contract.getReviewStatus())
                .lifecycleStatus(contract.getLifecycleStatus())
                .signedDate(contract.getSignedDate())
                .effectiveDate(contract.getEffectiveDate())
                .expiryDate(contract.getExpiryDate())
                .currency(contract.getCurrency())
                .totalContractValue(contract.getTotalContractValue())
                .currentVersion(contract.getCurrentVersion())
                .createdByAccountId(contract.getCreatedByAccountId())
                .createdAt(contract.getCreatedAt())
                .updatedByAccountId(contract.getUpdatedByAccountId())
                .updatedAt(contract.getUpdatedAt())
                .approvedByAccountId(contract.getApprovedByAccountId())
                .approvedAt(contract.getApprovedAt())
                .version(contract.getVersion())
                .build();
    }

    public static PartnerContractVersionResponse toVersionResponse(PartnerContractVersion version) {
        if (version == null) return null;
        return PartnerContractVersionResponse.builder()
                .id(version.getId())
                .contractId(version.getContractId())
                .referenceCompanyId(version.getReferenceCompanyId())
                .partnerCompanyId(version.getPartnerCompanyId())
                .sourceProjectId(version.getSourceProjectId())
                .sourceTaskId(version.getSourceTaskId())
                .rawDocumentId(version.getRawDocumentId())
                .contractNumber(version.getContractNumber())
                .contractTitle(version.getContractTitle())
                .contractType(version.getContractType())
                .reviewStatus(version.getReviewStatus())
                .lifecycleStatus(version.getLifecycleStatus())
                .signedDate(version.getSignedDate())
                .effectiveDate(version.getEffectiveDate())
                .expiryDate(version.getExpiryDate())
                .currency(version.getCurrency())
                .totalContractValue(version.getTotalContractValue())
                .createdByAccountId(version.getCreatedByAccountId())
                .createdAt(version.getCreatedAt())
                .updatedByAccountId(version.getUpdatedByAccountId())
                .updatedAt(version.getUpdatedAt())
                .approvedByAccountId(version.getApprovedByAccountId())
                .approvedAt(version.getApprovedAt())
                .version(version.getVersion())
                .build();
    }
}
