package com.apms.domain.profile.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.config.OwnerOrganizationProperties;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.profile.repository.mongo.CompanyProfileVersionRepository;
import com.apms.domain.profile.dto.OwnerProfileReadinessResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OwnerOrganizationServiceTest {

    @Mock
    private OwnerOrganizationProperties properties;

    @Mock
    private CompanyProfileRepository companyProfileRepository;

    @Mock
    private CompanyProfileVersionRepository versionRepository;

    @InjectMocks
    private OwnerOrganizationService ownerOrganizationService;

    private static final String DEFAULT_OWNER_ID = "6a31a0000000000000000001";

    @BeforeEach
    void setUp() {
        lenient().when(properties.getCompanyProfileId()).thenReturn(DEFAULT_OWNER_ID);
    }

    @Test
    void getOwnerCompanyId_ReturnsConfiguredId() {
        assertEquals(DEFAULT_OWNER_ID, ownerOrganizationService.getOwnerCompanyId());
    }

    @Test
    void findOwnerCompanyProfile_ReturnsEmpty_WhenNotFound() {
        when(companyProfileRepository.findById(DEFAULT_OWNER_ID)).thenReturn(Optional.empty());
        assertTrue(ownerOrganizationService.findOwnerCompanyProfile().isEmpty());
    }

    @Test
    void findOwnerCompanyProfile_ReturnsProfile_WhenFound() {
        CompanyProfile profile = new CompanyProfile();
        profile.setId(DEFAULT_OWNER_ID);
        when(companyProfileRepository.findById(DEFAULT_OWNER_ID)).thenReturn(Optional.of(profile));

        Optional<CompanyProfile> result = ownerOrganizationService.findOwnerCompanyProfile();
        assertTrue(result.isPresent());
        assertEquals(DEFAULT_OWNER_ID, result.get().getId());
    }

    @Test
    void getRequiredOwnerCompanyProfile_ThrowsException_WhenNotFound() {
        when(companyProfileRepository.findById(DEFAULT_OWNER_ID)).thenReturn(Optional.empty());

        BusinessValidationException exception = assertThrows(BusinessValidationException.class,
            () -> ownerOrganizationService.getRequiredOwnerCompanyProfile());

        assertTrue(exception.getMessage().contains("Owner CompanyProfile not found for ID:"));
    }

    @Test
    void isOwnerCompany_NullSafe() {
        assertFalse(ownerOrganizationService.isOwnerCompany(null));
        assertFalse(ownerOrganizationService.isOwnerCompany(""));
        assertFalse(ownerOrganizationService.isOwnerCompany("some-other-id"));
        assertTrue(ownerOrganizationService.isOwnerCompany(DEFAULT_OWNER_ID));
    }

    @Test
    void checkReadiness_ReturnsFalse_WhenProfileNotFound() {
        when(companyProfileRepository.findById(DEFAULT_OWNER_ID)).thenReturn(Optional.empty());
        OwnerProfileReadinessResponse response = ownerOrganizationService.checkReadiness();
        assertFalse(response.isReadyForComparison());
        assertTrue(response.getMissingSections().contains("CompanyProfile"));
    }
}
