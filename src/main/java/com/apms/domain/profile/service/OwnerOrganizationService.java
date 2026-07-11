package com.apms.domain.profile.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.config.OwnerOrganizationProperties;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.profile.dto.OwnerProfileReadinessResponse;
import com.apms.domain.profile.repository.mongo.CompanyProfileVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class OwnerOrganizationService {

    private final OwnerOrganizationProperties properties;
    private final CompanyProfileRepository companyProfileRepository;
    private final CompanyProfileVersionRepository versionRepository;

    /**
     * Gets the configured ID of the APMS Owner Organization.
     */
    public String getOwnerCompanyId() {
        return properties.getCompanyProfileId().trim();
    }

    /**
     * Finds the Owner Organization's profile, if it exists in the database.
     * Note: May be empty during transitional phases if the owner ID is a dummy value without a MongoDB record.
     */
    public Optional<CompanyProfile> findOwnerCompanyProfile() {
        return companyProfileRepository.findById(getOwnerCompanyId());
    }

    /**
     * Gets the Owner Organization's profile, throwing an exception if not found.
     */
    public CompanyProfile getRequiredOwnerCompanyProfile() {
        return findOwnerCompanyProfile()
                .orElseThrow(() -> new BusinessValidationException("Owner CompanyProfile not found for ID: " + getOwnerCompanyId()));
    }

    /**
     * Checks if the given company ID belongs to the Owner Organization.
     */
    public boolean isOwnerCompany(String companyId) {
        if (companyId == null) {
            return false;
        }
        return getOwnerCompanyId().equals(companyId.trim());
    }

    /**
     * Validates that the specified target company is NOT the Owner Organization.
     * Throws BusinessValidationException if it is.
     */
    public void validateTargetIsNotOwner(String targetCompanyProfileId) {
        if (isOwnerCompany(targetCompanyProfileId)) {
            throw new BusinessValidationException("Cannot use the APMS Owner Organization as a project target company.");
        }
    }

    /**
     * Resolves the Owner Organization's profile, ensuring it is approved.
     */
    public CompanyProfile resolveApprovedOwnerProfile() {
        CompanyProfile profile = getRequiredOwnerCompanyProfile();
        if (!"APPROVED".equals(profile.getReviewStatus())) {
            throw new BusinessValidationException("Owner CompanyProfile is not approved.");
        }
        return profile;
    }

    /**
     * Checks if the FPT owner profile is fully ready for factual comparison.
     */
    public OwnerProfileReadinessResponse checkReadiness() {
        Optional<CompanyProfile> profileOpt = findOwnerCompanyProfile();
        
        List<String> completed = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        boolean ready = true;

        if (profileOpt.isEmpty()) {
            missing.add("CompanyProfile");
            return OwnerProfileReadinessResponse.builder()
                    .companyProfileId(getOwnerCompanyId())
                    .approved(false)
                    .completedSections(completed)
                    .missingSections(missing)
                    .readyForComparison(false)
                    .build();
        }

        CompanyProfile profile = profileOpt.get();
        completed.add("CompanyProfile");

        boolean isApproved = "APPROVED".equals(profile.getReviewStatus());
        if (isApproved) {
            completed.add("ReviewStatus:APPROVED");
        } else {
            missing.add("ReviewStatus:APPROVED");
            ready = false;
        }

        if (profile.getIdentity() != null && StringUtils.hasText(profile.getIdentity().getLegalName())) {
            completed.add("Identity.LegalName");
        } else {
            missing.add("Identity.LegalName");
            ready = false;
        }

        if (profile.getBusiness() != null) {
            if (!CollectionUtils.isEmpty(profile.getBusiness().getIndustries())) {
                completed.add("Business.Industries");
            } else {
                missing.add("Business.Industries");
                ready = false;
            }
            if (!CollectionUtils.isEmpty(profile.getBusiness().getProducts())) {
                completed.add("Business.Products");
            } else {
                missing.add("Business.Products");
                ready = false;
            }
            if (!CollectionUtils.isEmpty(profile.getBusiness().getMarkets())) {
                completed.add("Business.Markets");
            } else {
                missing.add("Business.Markets");
                ready = false;
            }
            if (!CollectionUtils.isEmpty(profile.getBusiness().getTargetCustomers())) {
                completed.add("Business.TargetCustomers");
            } else {
                missing.add("Business.TargetCustomers");
            }
        } else {
            missing.add("Business");
            ready = false;
        }

        if (profile.getVersion() != null) {
            completed.add("CompanyProfile.Version");
            boolean hasVersionSnapshot = versionRepository.findByCompanyProfileIdAndVersion(profile.getId(), profile.getVersion()).isPresent();
            if (hasVersionSnapshot) {
                completed.add("CompanyProfileVersionSnapshot");
            } else {
                missing.add("CompanyProfileVersionSnapshot");
                ready = false;
            }
        } else {
            missing.add("CompanyProfile.Version");
            ready = false;
        }

        // Optional factual sections
        if (profile.getFinancial() != null) completed.add("FinancialInfo"); else missing.add("FinancialInfo");
        if (profile.getMarket() != null) completed.add("MarketInfo"); else missing.add("MarketInfo");
        if (profile.getInnovation() != null) completed.add("InnovationInfo"); else missing.add("InnovationInfo");
        if (profile.getRisk() != null) completed.add("RiskInfo"); else missing.add("RiskInfo");
        if (profile.getCompliance() != null) completed.add("ComplianceInfo"); else missing.add("ComplianceInfo");

        return OwnerProfileReadinessResponse.builder()
                .companyProfileId(profile.getId())
                .profileVersion(profile.getVersion())
                .approved(isApproved)
                .completedSections(completed)
                .missingSections(missing)
                .readyForComparison(ready)
                .build();
    }
}
