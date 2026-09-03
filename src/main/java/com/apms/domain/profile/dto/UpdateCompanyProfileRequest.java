package com.apms.domain.profile.dto;

import com.apms.domain.profile.CompanyProfile;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateCompanyProfileRequest {
    // Identity
    private String legalName;
    private String tradeName;
    private String taxCode;
    private String registrationNumber;

    // Contact
    private String website;
    private List<String> emails;
    private List<String> phones;
    private String headOfficeAddress;

    // Company Size
    private String employeeTier;
    private Integer employeeCount;
    private String revenueTier;

    // Business
    private List<String> industries;
    private List<String> markets;
    private List<String> targetCustomers;
    private List<String> productsServices;
    private String description;
    private String businessModel;

    // Leadership
    private List<CompanyProfile.CompanyMember> companyMembers;

    // Tags
    private List<String> tags;

    // Optimistic concurrency & history metadata
    private Integer expectedMajorVersion;
    private Integer expectedRevision;
    private String changeNote;
}
