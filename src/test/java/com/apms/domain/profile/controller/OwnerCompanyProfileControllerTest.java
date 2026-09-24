package com.apms.domain.profile.controller;

import com.apms.common.response.ApiResponse;
import com.apms.domain.profile.dto.OwnerProfileReadinessResponse;
import com.apms.domain.profile.dto.ProfileResponse;
import com.apms.domain.profile.dto.ReferenceCompanyContextResponse;
import com.apms.domain.profile.service.CompanyProfileVersionService;
import com.apms.domain.profile.service.OwnerOrganizationService;
import com.apms.domain.profile.service.ProfileService;
import com.apms.domain.profile.service.ReferenceCompanyContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class OwnerCompanyProfileControllerTest {

    private MockMvc mockMvc;

    @Mock
    private OwnerOrganizationService ownerOrganizationService;

    @Mock
    private ProfileService profileService;

    @Mock
    private CompanyProfileVersionService versionService;

    @Mock
    private ReferenceCompanyContextService referenceCompanyContextService;

    @InjectMocks
    private OwnerCompanyProfileController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void getOwnerCompanyProfile_ShouldReturnProfile() throws Exception {
        when(ownerOrganizationService.getOwnerCompanyId()).thenReturn("fpt-id");
        when(profileService.getApprovedProfileResponse("fpt-id"))
                .thenReturn(ProfileResponse.builder().companyId("fpt-id").build());

        mockMvc.perform(get("/api/v1/owner/company-profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.companyId").value("fpt-id"));
    }

    @Test
    void getOwnerCompanyProfileReadiness_ShouldReturnReadiness() throws Exception {
        when(ownerOrganizationService.checkReadiness())
                .thenReturn(OwnerProfileReadinessResponse.builder().readyForComparison(true).build());

        mockMvc.perform(get("/api/v1/owner/company-profile/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.readyForComparison").value(true));
    }

    @Test
    void getReferenceContext_ShouldReturnContext() throws Exception {
        when(referenceCompanyContextService.getReferenceCompanyContext())
                .thenReturn(ReferenceCompanyContextResponse.builder().companyProfileId("fpt-id").build());

        mockMvc.perform(get("/api/v1/owner/reference-context"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.companyProfileId").value("fpt-id"));
    }
}
