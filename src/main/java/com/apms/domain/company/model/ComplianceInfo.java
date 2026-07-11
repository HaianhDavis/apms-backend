package com.apms.domain.company.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComplianceInfo {
    private String status;
    private List<String> qualityCertifications;
    private List<String> securityCertifications;
    private String antiCorruptionPolicy;
    private String laborCompliance;
    private String environmentalPolicy;
}
