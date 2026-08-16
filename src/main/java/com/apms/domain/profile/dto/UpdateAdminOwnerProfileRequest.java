package com.apms.domain.profile.dto;

import com.apms.domain.profile.CompanyProfile;
import lombok.Data;

import java.util.List;

@Data
public class UpdateAdminOwnerProfileRequest {
    private String legalName;
    private String tradeName;
    private String taxCode;
    private String registrationNumber;
    private String stockTicker;
    private String stockExchange;
    private String website;
    private List<String> emails;
    private List<String> phones;
    private String employeeTier;
    private Integer employeeCount;
    private String revenueTier;
    private String address;
    private String businessModel;
    private List<String> industries;
    private CompanyProfile.Insights insights;
    private List<CompanyProfile.Product> products;
    private List<String> markets;
    private List<String> targetCustomers;
    private List<CompanyProfile.CompanyMember> companyMembers;
}
