package com.apms.domain.score.service;

import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.profile.repository.mongo.CompanyProfileVersionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompanyProfileIdentifierResolverTest {

    @Mock
    private CompanyProfileRepository companyProfileRepository;
    @Mock
    private CompanyProfileVersionRepository companyProfileVersionRepository;

    @InjectMocks
    private CompanyProfileIdentifierResolver resolver;

    /**
     * REQ: profile.id (Mongo _id) and profile.companyId (UUID) may differ.
     */
    @Test
    void profileIdAndCompanyIdAreIndependent() {
        CompanyProfile profile = new CompanyProfile();
        profile.setId("mongo-object-id-abc123");
        profile.setCompanyId("uuid-company-id-XYZ");

        assertNotEquals(profile.getId(), profile.getCompanyId(),
                "Mongo _id and companyId UUID must be distinct");
    }

    /**
     * REQ: resolveTargetProfile uses companyId (UUID) from Project.targetCompanyProfileId.
     */
    @Test
    void resolveTargetProfile_UsesCompanyId() {
        CompanyProfile profile = new CompanyProfile();
        profile.setId("mongo-doc-id");
        profile.setCompanyId("company-uuid");

        when(companyProfileRepository.findByCompanyId("company-uuid")).thenReturn(Optional.of(profile));

        CompanyProfile result = resolver.resolveTargetProfile("company-uuid");

        assertNotNull(result);
        assertEquals("mongo-doc-id", result.getId());
        assertEquals("company-uuid", result.getCompanyId());
        verify(companyProfileRepository).findByCompanyId("company-uuid");
    }

    /**
     * REQ: resolveProfileByDocumentId uses Mongo _id (e.g. owner profile lookup).
     */
    @Test
    void resolveProfileByDocumentId_UsesMongo_id() {
        CompanyProfile profile = new CompanyProfile();
        profile.setId("6a31a0000000000000000001");
        profile.setCompanyId("fpt-company-uuid");

        when(companyProfileRepository.findById("6a31a0000000000000000001")).thenReturn(Optional.of(profile));

        CompanyProfile result = resolver.resolveProfileByDocumentId("6a31a0000000000000000001");

        assertNotNull(result);
        assertEquals("6a31a0000000000000000001", result.getId());
        assertEquals("fpt-company-uuid", result.getCompanyId());
        verify(companyProfileRepository).findById("6a31a0000000000000000001");
    }

    @Test
    void resolveTargetProfile_NotFound_ThrowsException() {
        when(companyProfileRepository.findByCompanyId("nonexistent")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () ->
                resolver.resolveTargetProfile("nonexistent"));
    }

    @Test
    void resolveProfileByDocumentId_NotFound_ThrowsException() {
        when(companyProfileRepository.findById("bad-id")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () ->
                resolver.resolveProfileByDocumentId("bad-id"));
    }
}
