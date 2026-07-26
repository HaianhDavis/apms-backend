package com.apms.domain.profile.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.CompanyProfileVersion;
import com.apms.domain.profile.dto.ComparisonInputAvailabilityResponse;
import com.apms.domain.profile.dto.OwnerProfileReadinessResponse;
import com.apms.domain.profile.dto.ReferenceCompanyContextResponse;
import com.apms.domain.profile.repository.mongo.CompanyProfileVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReferenceCompanyContextServiceTest {

    @Mock
    private OwnerOrganizationService ownerOrganizationService;

    @Mock
    private CompanyProfileVersionRepository versionRepository;

    @InjectMocks
    private ReferenceCompanyContextService referenceCompanyContextService;

    private CompanyProfile ownerProfile;

    @BeforeEach
    void setUp() {
        ownerProfile = new CompanyProfile();
        ownerProfile.setId("fpt-id");
        ownerProfile.setReviewStatus("APPROVED");
        ownerProfile.setVersion(1);
    }

    @Test
    void getReferenceCompanyContext_ShouldReturnContext_WhenFullyReady() {
        // Arrange
        ownerProfile.setIdentity(CompanyProfile.Identity.builder()
                .legalName(" FPT Corporation ")
                .tradeName("FPT")
                .build());
        ownerProfile.setBusiness(CompanyProfile.Business.builder()
                .industries(List.of("IT Services", " IT Services ", ""))
                .markets(List.of("Vietnam", "Global"))
                .products(List.of(CompanyProfile.Product.builder().name("Software").build()))
                .build());

        when(ownerOrganizationService.resolveApprovedOwnerProfile()).thenReturn(ownerProfile);
        when(versionRepository.findByCompanyProfileIdAndVersion("fpt-id", 1))
                .thenReturn(Optional.of(CompanyProfileVersion.builder().build()));
        when(ownerOrganizationService.checkReadiness())
                .thenReturn(OwnerProfileReadinessResponse.builder().readyForComparison(true).build());

        // Act
        ReferenceCompanyContextResponse response = referenceCompanyContextService.getReferenceCompanyContext();

        // Assert
        assertThat(response.getCompanyProfileId()).isEqualTo("fpt-id");
        assertThat(response.getLegalName()).isEqualTo("FPT Corporation");
        assertThat(response.getIndustries()).containsExactly("IT Services");

        Map<String, ComparisonInputAvailabilityResponse> availability = response.getComparisonInputAvailability();
        assertThat(availability.get("strategicFit").isAvailable()).isTrue();
        assertThat(availability.get("capabilityComplementarity").isAvailable()).isFalse();
    }

    @Test
    void getReferenceCompanyContext_ShouldThrowException_WhenVersionMissing() {
        ownerProfile.setVersion(null);
        when(ownerOrganizationService.resolveApprovedOwnerProfile()).thenReturn(ownerProfile);

        assertThatThrownBy(() -> referenceCompanyContextService.getReferenceCompanyContext())
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("version snapshot was not found");
    }
}
