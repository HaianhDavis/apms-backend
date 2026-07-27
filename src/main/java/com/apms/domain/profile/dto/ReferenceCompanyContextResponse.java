package com.apms.domain.profile.dto;

import com.apms.domain.company.model.ComplianceInfo;
import com.apms.domain.company.model.FinancialInfo;
import com.apms.domain.company.model.InnovationInfo;
import com.apms.domain.company.model.MarketInfo;
import com.apms.domain.company.model.RiskInfo;
import com.apms.domain.profile.CompanyProfile;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReferenceCompanyContextResponse {
    private String companyProfileId;
    private Integer profileVersion;

    private String legalName;
    private String tradeName;

    private List<String> industries;
    private String businessModel;
    private List<CompanyProfile.Product> products;
    private List<String> markets;
    private List<String> targetCustomers;

    private Integer employeeCount;
    private String employeeTier;
    private String revenueTier;

    private List<CompanyProfile.Address> headquarters; // actually List<Address>
    private String website;

    private FinancialInfo financial;
    private MarketInfo market;
    private InnovationInfo innovation;
    private RiskInfo risk;
    private ComplianceInfo compliance;

    private Set<String> sourceDocumentIds;

    private OwnerProfileReadinessResponse readiness;
    private Map<String, ComparisonInputAvailabilityResponse> comparisonInputAvailability;

    private LocalDateTime generatedAt;
}
