package com.apms.domain.profile.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.config.OwnerOrganizationProperties;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class OwnerOrganizationService {

    private final OwnerOrganizationProperties properties;
    private final CompanyProfileRepository companyProfileRepository;

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
}
