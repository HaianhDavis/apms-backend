package com.apms.domain.profile.service;

import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.dto.CompanyIdentity;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * Centralized service to resolve company identifiers.
 * Accepts either MongoDB _id or universal Company UUID and returns
 * the canonical CompanyIdentity.
 */
@Component
@RequiredArgsConstructor
public class CompanyIdentityResolver {

    private final CompanyProfileRepository profileRepository;

    public Optional<CompanyIdentity> resolve(String inputId) {
        if (!StringUtils.hasText(inputId)) {
            return Optional.empty();
        }
        String trimmed = inputId.trim();
        Optional<CompanyProfile> profileOpt = profileRepository.findByCompanyId(trimmed)
                .or(() -> profileRepository.findById(trimmed));

        if (profileOpt.isEmpty()) {
            return Optional.empty();
        }

        CompanyProfile profile = profileOpt.get();
        return Optional.of(CompanyIdentity.builder()
                .profileId(profile.getId())
                .companyId(profile.getCompanyId() != null && !profile.getCompanyId().isBlank() ? profile.getCompanyId() : profile.getId())
                .profile(profile)
                .build());
    }
}
