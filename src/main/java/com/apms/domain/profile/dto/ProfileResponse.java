package com.apms.domain.profile.dto;

import com.apms.domain.profile.CompanyProfile;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ProfileResponse {
    private String id;
    private String companyId;

    private CompanyProfile.Identity identity;
    private CompanyProfile.Business business;
    private CompanyProfile.CompanySize companySize;
    private CompanyProfile.Contact contact;
    private CompanyProfile.Insights insights;

    private com.apms.domain.company.model.FinancialInfo financial;
    private com.apms.domain.company.model.MarketInfo market;
    private com.apms.domain.company.model.InnovationInfo innovation;
    private com.apms.domain.company.model.RiskInfo risk;
    private com.apms.domain.company.model.ComplianceInfo compliance;
    private List<CompanyProfile.CompanyMember> companyMembers;

    private String reviewStatus;
    private String relationshipType;
    private List<String> tags;
    private CompanyProfile.Metadata metadata;
    private String version;
    private Long responsibleManagerId;
}
