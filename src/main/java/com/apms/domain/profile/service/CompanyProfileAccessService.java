package com.apms.domain.profile.service;

import com.apms.common.enums.SystemRole;
import com.apms.common.exception.BusinessValidationException;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyProfileAccessService {

    private final CompanyProfileRepository companyProfileRepository;

    /**
     * Ensures that the requested company profile exists, is official/approved,
     * is not deleted or hidden, and the user is a BUSINESS_OWNER.
     * Does NOT check project membership.
     *
     * @param companyProfileId the ID of the company profile
     * @param user             the current authenticated user
     * @return the CompanyProfile if accessible
     */
    public CompanyProfile requireOwnerAccessibleOfficialCompanyProfile(String companyProfileId, UserDetailsImpl user) {
        if (!hasRole(user, SystemRole.BUSINESS_OWNER)) {
            throw new AccessDeniedException("ACCESS_FORBIDDEN");
        }

        CompanyProfile profile = companyProfileRepository.findByCompanyId(companyProfileId)
                .orElseThrow(() -> new ResourceNotFoundException("COMPANY_PROFILE_NOT_FOUND"));

        if (Boolean.TRUE.equals(profile.getIsDeleted())) {
            throw new ResourceNotFoundException("COMPANY_PROFILE_NOT_FOUND");
        }

        if (Boolean.TRUE.equals(profile.getIsHidden())) {
            throw new AccessDeniedException("COMPANY_PROFILE_ACCESS_DENIED");
        }

        if (!"APPROVED".equals(profile.getReviewStatus())) {
            throw new BusinessValidationException("COMPANY_PROFILE_NOT_OFFICIAL");
        }

        return profile;
    }

    private boolean hasRole(UserDetailsImpl user, SystemRole role) {
        return user.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_" + role.name()));
    }
}
