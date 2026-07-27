package com.apms.domain.score.service;

import com.apms.domain.profile.CompanyProfileVersion;
import com.apms.domain.profile.repository.mongo.CompanyProfileVersionRepository;
import com.apms.domain.score.draft.ApprovedSourceReference;
import com.apms.domain.score.enums.ApprovedSourceType;
import com.apms.common.exception.BusinessValidationException;
import com.apms.common.exception.ResourceNotFoundException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ApprovedSourceReferenceFactory {

    private final CompanyProfileVersionRepository companyProfileVersionRepository;
    private final ObjectMapper objectMapper;

    public ApprovedSourceReference createFromCompanyProfileVersion(String companyProfileVersionId, Long evaluationProjectId, String expectedCompanyId) {
        if (companyProfileVersionId == null) {
            throw new IllegalArgumentException("companyProfileVersionId cannot be null");
        }
        CompanyProfileVersion version = companyProfileVersionRepository.findById(companyProfileVersionId)
                .orElseThrow(() -> new ResourceNotFoundException("CompanyProfileVersion not found: " + companyProfileVersionId));

        if (!expectedCompanyId.equals(version.getCompanyId())) {
            throw new BusinessValidationException("Target company alignment failed: CompanyProfileVersion belongs to " + version.getCompanyId() + ", expected " + expectedCompanyId);
        }

        String sourceHash;
        try {
            if (version.getSnapshot() == null || version.getSnapshot().isEmpty()) {
                throw new BusinessValidationException("CompanyProfileVersion snapshot cannot be null or empty");
            }

            ObjectMapper hashingMapper = objectMapper.copy()
                    .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

            byte[] bytes = hashingMapper.writeValueAsBytes(version.getSnapshot());

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(bytes);

            StringBuilder hexString = new StringBuilder(2 * hashBytes.length);
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            sourceHash = hexString.toString();
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to hash CompanyProfileVersion snapshot", e);
        }

        ApprovedSourceReference ref = ApprovedSourceReference.builder()
                .referenceId(java.util.UUID.randomUUID().toString())
                .sourceType(ApprovedSourceType.COMPANY_PROFILE_VERSION)
                .mongoSourceId(version.getId())
                .sourceVersionNumber(version.getVersion())
                .projectId(evaluationProjectId)
                .companyProfileId(version.getCompanyProfileId())
                .sourceHash(sourceHash)
                .pinnedAt(LocalDateTime.now())
                .build();

        ref.validate();
        return ref;
    }
}
