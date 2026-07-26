package com.apms.domain.score.service;

import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.CompanyProfileVersion;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.profile.repository.mongo.CompanyProfileVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CompanyProfileIdentifierResolver {

    private final CompanyProfileRepository companyProfileRepository;
    private final CompanyProfileVersionRepository companyProfileVersionRepository;

    /**
     * Resolves the stable companyId (UUID) to the actual CompanyProfile.
     * Use this when looking up a profile from Project.targetCompanyProfileId.
     */
    public CompanyProfile resolveTargetProfile(String targetCompanyId) {
        return companyProfileRepository.findByCompanyId(targetCompanyId)
                .orElseThrow(() -> new IllegalArgumentException("Target profile not found for companyId: " + targetCompanyId));
    }

    /**
     * Resolves a profile by its MongoDB _id.
     * Use this when looking up the Owner/FPT profile from the configured ID.
     */
    public CompanyProfile resolveProfileByDocumentId(String documentId) {
        return companyProfileRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Profile not found for document ID: " + documentId));
    }

    /**
     * Resolves the profile version using the MongoDB _id (which is expected by the version repository).
     */
    public CompanyProfileVersion resolveVersion(String profileDocumentId, Integer version) {
        return companyProfileVersionRepository.findByCompanyProfileIdAndVersion(profileDocumentId, version)
                .orElseThrow(() -> new IllegalArgumentException("Profile version not found: " + profileDocumentId + " v" + version));
    }
}
