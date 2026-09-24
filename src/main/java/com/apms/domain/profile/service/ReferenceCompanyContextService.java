package com.apms.domain.profile.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.dto.ComparisonInputAvailabilityResponse;
import com.apms.domain.profile.dto.OwnerProfileReadinessResponse;
import com.apms.domain.profile.dto.ReferenceCompanyContextResponse;
import com.apms.domain.profile.repository.mongo.CompanyProfileVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReferenceCompanyContextService {

    private final OwnerOrganizationService ownerOrganizationService;
    private final CompanyProfileVersionRepository versionRepository;

    public ReferenceCompanyContextResponse getReferenceCompanyContext() {
        CompanyProfile profile = ownerOrganizationService.resolveApprovedOwnerProfile();

        if (profile.getVersion() == null) {
            throw new BusinessValidationException("Owner CompanyProfile version snapshot was not found.");
        }

        boolean hasVersionSnapshot = versionRepository.findByCompanyProfileIdAndVersion(profile.getId(), profile.getVersion()).isPresent();
        if (!hasVersionSnapshot) {
            throw new BusinessValidationException("Owner CompanyProfile version snapshot was not found.");
        }

        OwnerProfileReadinessResponse readiness = ownerOrganizationService.checkReadiness();

        Map<String, ComparisonInputAvailabilityResponse> availability = calculateAvailability(profile);

        return ReferenceCompanyContextResponse.builder()
                .companyProfileId(profile.getId())
                .profileVersion(profile.getVersion())
                .legalName(profile.getIdentity() != null ? normalizeString(profile.getIdentity().getLegalName()) : null)
                .tradeName(profile.getIdentity() != null ? normalizeString(profile.getIdentity().getTradeName()) : null)
                .industries(profile.getBusiness() != null ? normalizeList(profile.getBusiness().getIndustries()) : new ArrayList<>())
                .businessModel(profile.getBusiness() != null ? normalizeString(profile.getBusiness().getBusinessModel()) : null)
                .products(profile.getBusiness() != null && profile.getBusiness().getProducts() != null ? profile.getBusiness().getProducts() : new ArrayList<>())
                .markets(profile.getBusiness() != null ? normalizeList(profile.getBusiness().getMarkets()) : new ArrayList<>())
                .targetCustomers(profile.getBusiness() != null ? normalizeList(profile.getBusiness().getTargetCustomers()) : new ArrayList<>())
                .employeeCount(profile.getCompanySize() != null ? profile.getCompanySize().getEmployeeCount() : null)
                .employeeTier(profile.getCompanySize() != null ? profile.getCompanySize().getEmployeeTier() : null)
                .revenueTier(profile.getCompanySize() != null ? profile.getCompanySize().getRevenueTier() : null)
                .headquarters(profile.getContact() != null && profile.getContact().getAddresses() != null ? profile.getContact().getAddresses() : new ArrayList<>())
                .website(profile.getContact() != null ? normalizeString(profile.getContact().getWebsite()) : null)
                .financial(profile.getFinancial())
                .market(profile.getMarket())
                .innovation(profile.getInnovation())
                .risk(profile.getRisk())
                .compliance(profile.getCompliance())
                .sourceDocumentIds(profile.getSourceRefs() != null ? profile.getSourceRefs().getRawDocumentIds() : null)
                .readiness(readiness)
                .comparisonInputAvailability(availability)
                .generatedAt(LocalDateTime.now())
                .build();
    }

    private Map<String, ComparisonInputAvailabilityResponse> calculateAvailability(CompanyProfile p) {
        Map<String, ComparisonInputAvailabilityResponse> map = new HashMap<>();

        // 1. strategicFit
        boolean sfHasInd = p.getBusiness() != null && !CollectionUtils.isEmpty(p.getBusiness().getIndustries());
        boolean sfHasProd = p.getBusiness() != null && !CollectionUtils.isEmpty(p.getBusiness().getProducts());
        boolean sfHasMarket = p.getBusiness() != null && !CollectionUtils.isEmpty(p.getBusiness().getMarkets());
        map.put("strategicFit", buildAvailability(sfHasInd && sfHasProd && sfHasMarket,
                List.of("business.industries", "business.products", "business.markets"),
                getMissingFields(
                    !sfHasInd, "business.industries",
                    !sfHasProd, "business.products",
                    !sfHasMarket, "business.markets"
                )));

        // 2. capabilityComplementarity
        boolean ccHasTech = p.getInnovation() != null && !CollectionUtils.isEmpty(p.getInnovation().getTechnologyCapabilities());
        map.put("capabilityComplementarity", buildAvailability(ccHasTech,
                List.of("innovation.technologyCapabilities"),
                getMissingFields(!ccHasTech, "innovation.technologyCapabilities")));

        // 3. productMarketOverlap
        map.put("productMarketOverlap", buildAvailability(sfHasProd && sfHasMarket,
                List.of("business.products", "business.markets"),
                getMissingFields(
                    !sfHasProd, "business.products",
                    !sfHasMarket, "business.markets"
                )));

        // 4. competitiveCapabilityComparison
        boolean hasEmpCount = p.getCompanySize() != null && p.getCompanySize().getEmployeeCount() != null;
        boolean hasEmpTier = p.getCompanySize() != null && StringUtils.hasText(p.getCompanySize().getEmployeeTier());
        boolean hasSize = hasEmpCount || hasEmpTier;
        map.put("competitiveCapabilityComparison", buildAvailability(ccHasTech && hasSize,
                List.of("innovation.technologyCapabilities", "companySize.employeeCount OR companySize.employeeTier"),
                getMissingFields(
                    !ccHasTech, "innovation.technologyCapabilities",
                    !hasSize, "companySize.employeeCount OR companySize.employeeTier"
                )));

        // 5. marketPositionComparison
        boolean hasMarketShare = p.getMarket() != null && p.getMarket().getMarketShare() != null;
        boolean hasBrandRank = p.getMarket() != null && p.getMarket().getBrandRank() != null;
        boolean hasClientCount = p.getMarket() != null && p.getMarket().getClientCount() != null;
        boolean hasMarketPos = hasMarketShare || hasBrandRank || hasClientCount;
        map.put("marketPositionComparison", buildAvailability(hasMarketPos,
                List.of("market.marketShare OR market.brandRank OR market.clientCount"),
                getMissingFields(!hasMarketPos, "market.marketShare OR market.brandRank OR market.clientCount")));

        // 6. financialComparison
        boolean hasRev = p.getFinancial() != null && p.getFinancial().getRevenue() != null;
        boolean hasProf = p.getFinancial() != null && p.getFinancial().getProfitMargin() != null;
        boolean hasGrow = p.getFinancial() != null && p.getFinancial().getRevenueGrowth() != null;
        boolean hasFin = hasRev || hasProf || hasGrow;
        map.put("financialComparison", buildAvailability(hasFin,
                List.of("financial.revenue OR financial.profitMargin OR financial.revenueGrowth"),
                getMissingFields(!hasFin, "financial.revenue OR financial.profitMargin OR financial.revenueGrowth")));

        // 7. complianceComparison
        boolean hasStatus = p.getCompliance() != null && StringUtils.hasText(p.getCompliance().getStatus());
        boolean hasQual = p.getCompliance() != null && !CollectionUtils.isEmpty(p.getCompliance().getQualityCertifications());
        boolean hasSec = p.getCompliance() != null && !CollectionUtils.isEmpty(p.getCompliance().getSecurityCertifications());
        boolean hasComp = hasStatus || hasQual || hasSec;
        map.put("complianceComparison", buildAvailability(hasComp,
                List.of("compliance.status OR compliance.qualityCertifications OR compliance.securityCertifications"),
                getMissingFields(!hasComp, "compliance.status OR compliance.qualityCertifications OR compliance.securityCertifications")));

        return map;
    }

    private ComparisonInputAvailabilityResponse buildAvailability(boolean available, List<String> required, List<String> missing) {
        return ComparisonInputAvailabilityResponse.builder()
                .available(available)
                .requiredOwnerFields(required)
                .missingOwnerFields(missing)
                .build();
    }

    private List<String> getMissingFields(Object... conditionsAndNames) {
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < conditionsAndNames.length; i += 2) {
            boolean isMissing = (Boolean) conditionsAndNames[i];
            if (isMissing) {
                missing.add((String) conditionsAndNames[i + 1]);
            }
        }
        return missing;
    }

    private String normalizeString(String input) {
        return input == null ? null : input.trim();
    }

    private List<String> normalizeList(List<String> input) {
        if (input == null) return new ArrayList<>();
        return input.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .collect(Collectors.toList());
    }
}
