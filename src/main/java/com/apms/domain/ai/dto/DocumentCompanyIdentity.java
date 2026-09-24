package com.apms.domain.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentCompanyIdentity {
    private String rawDocumentId;
    private String fileName;
    private String legalName;
    private String tradeName;
    private String taxCode;
    private String registrationNumber;
    private String websiteDomain;
    private String status;
    private double confidence;
}
