package com.apms.domain.profile.dto;

import com.apms.domain.profile.CompanyProfile;
import com.apms.common.enums.ProfileVisibility;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
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
    private ProfileVisibility visibility;
    @com.fasterxml.jackson.annotation.JsonProperty("isHidden")
    private Boolean isHidden;
    private String relationshipType;
    private List<String> tags;
    private CompanyProfile.Metadata metadata;
    private String version;
    private Integer majorVersion;
    private Integer revision;
    private String versionLabel;
    private Long responsibleManagerId;
    private Boolean canEditProfile;
    private Boolean canManageVisibility;
    private Boolean canPublish;
    private String publishBlockReason;
    private Boolean canAccessRelationshipCloseness;
}
