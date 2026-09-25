package com.apms.domain.profile.controller;

import com.apms.config.SecurityConfig;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.dto.AdminUpdateEnterpriseBasicInfoRequest;
import com.apms.domain.profile.dto.ProfileResponse;
import com.apms.domain.profile.service.OwnerOrganizationService;
import com.apms.domain.profile.service.ProfileService;
import com.apms.security.AuthEntryPointJwt;
import com.apms.security.AuthTokenFilter;
import com.apms.security.JwtUtils;
import com.apms.security.UserDetailsServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminMyEnterpriseController.class)
@Import(SecurityConfig.class)
@AutoConfigureMockMvc(addFilters = true)
@EnableMethodSecurity
class AdminMyEnterpriseControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProfileService profileService;

    @MockitoBean
    private OwnerOrganizationService ownerOrganizationService;

    @MockitoBean
    private com.apms.domain.profile.service.AdminMyEnterpriseFinancialService enterpriseFinancialService;

    @MockitoBean
    private com.apms.domain.document.service.StorageService storageService;

    @MockitoBean
    private UserDetailsServiceImpl userDetailsService;

    @MockitoBean
    private AuthEntryPointJwt unauthorizedHandler;

    @MockitoBean
    private AuthTokenFilter authTokenFilter;

    @MockitoBean
    private JwtUtils jwtUtils;

    @TestConfiguration
    static class TestConfig {
        @Bean
        com.apms.common.security.ProjectSecurityEvaluator projectSecurity() {
            return org.mockito.Mockito.mock(com.apms.common.security.ProjectSecurityEvaluator.class);
        }
    }

    @BeforeEach
    void setup() throws Exception {
        doAnswer(invocation -> {
            jakarta.servlet.FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(authTokenFilter).doFilter(any(), any(), any());

        doAnswer(invocation -> {
            jakarta.servlet.http.HttpServletResponse response = invocation.getArgument(1);
            response.sendError(jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED, "Error: Unauthorized");
            return null;
        }).when(unauthorizedHandler).commence(any(), any(), any());
    }

    private static final String ENDPOINT = "/api/v1/admin/my-enterprise";
    private static final String BASIC_INFO_ENDPOINT = ENDPOINT + "/basic-info";
    private static final String BUSINESS_FIELDS_ENDPOINT = ENDPOINT + "/business-fields";
    private static final String LEADERSHIP_ENDPOINT = ENDPOINT + "/leadership";

    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_SYSTEM_ADMIN"})
    void admin_getAdminMyEnterprise_returnsOk() throws Exception {
        CompanyProfile profile = CompanyProfile.builder().id("owner-fpt-id").build();
        when(ownerOrganizationService.findOwnerCompanyProfile()).thenReturn(java.util.Optional.of(profile));
        when(profileService.toResponse(profile))
                .thenReturn(ProfileResponse.builder()
                        .companyId("owner-fpt-id")
                        .identity(CompanyProfile.Identity.builder().legalName("FPT Corporation").build())
                        .build());

        mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isOk());

        verify(profileService).toResponse(profile);
    }

    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_SYSTEM_ADMIN"})
    void admin_getAdminMyEnterprise_unconfigured_returnsOk() throws Exception {
        when(ownerOrganizationService.findOwnerCompanyProfile()).thenReturn(java.util.Optional.empty());

        mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_SYSTEM_ADMIN"})
    void admin_createAdminMyEnterprise_returnsOk() throws Exception {
        CompanyProfile created = CompanyProfile.builder().id("new-owner-id").build();
        when(ownerOrganizationService.createOwnerEnterprise(any(), any())).thenReturn(created);
        when(profileService.toResponse(created))
                .thenReturn(ProfileResponse.builder().id("new-owner-id").build());

        String json = """
                {
                    "legalName": "My Enterprise Corp",
                    "tradeName": "My Corp",
                    "taxCode": "0123456789"
                }
                """;

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_SYSTEM_ADMIN"})
    void admin_updateAdminEnterpriseBasicInfo_returnsOk() throws Exception {
        when(profileService.updateAdminEnterpriseBasicInfo(any(), any()))
                .thenReturn(ProfileResponse.builder()
                        .companyId("owner-fpt-id")
                        .identity(CompanyProfile.Identity.builder().legalName("FPT Updated").build())
                        .build());

        String json = """
                {
                    "legalName": "FPT Updated",
                    "tradeName": "FPT",
                    "taxCode": "0101234567",
                    "email": "contact@fpt.com.vn",
                    "phone": "02473007300",
                    "website": "https://fpt.com.vn",
                    "headOfficeAddress": "10 Pham Van Bach",
                    "employeeCount": 48000,
                    "employeeTier": "ENTERPRISE",
                    "businessModel": "Leading ICT group in Vietnam",
                    "expectedMajorVersion": 1,
                    "expectedRevision": 0
                }
                """;

        mockMvc.perform(patch(BASIC_INFO_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());

        ArgumentCaptor<AdminUpdateEnterpriseBasicInfoRequest> captor =
                ArgumentCaptor.forClass(AdminUpdateEnterpriseBasicInfoRequest.class);
        verify(profileService).updateAdminEnterpriseBasicInfo(captor.capture(), any());
        AdminUpdateEnterpriseBasicInfoRequest captured = captor.getValue();
        assertThat(captured.getLegalName()).isEqualTo("FPT Updated");
        assertThat(captured.getTradeName()).isEqualTo("FPT");
        assertThat(captured.getTaxCode()).isEqualTo("0101234567");
        assertThat(captured.getPhone()).isEqualTo("02473007300");
        assertThat(captured.getEmail()).isEqualTo("contact@fpt.com.vn");
        assertThat(captured.getWebsite()).isEqualTo("https://fpt.com.vn");
        assertThat(captured.getHeadOfficeAddress()).isEqualTo("10 Pham Van Bach");
        assertThat(captured.getEmployeeCount()).isEqualTo(48000);
        assertThat(captured.getEmployeeTier()).isEqualTo("ENTERPRISE");
        assertThat(captured.getBusinessModel()).isEqualTo("Leading ICT group in Vietnam");
        assertThat(captured.getExpectedMajorVersion()).isEqualTo(1);
        assertThat(captured.getExpectedRevision()).isEqualTo(0);
    }

    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_SYSTEM_ADMIN"})
    void admin_updateAdminEnterpriseBasicInfo_invalidEmail_returnsBadRequest() throws Exception {
        String json = """
                {
                    "email": "not-a-valid-email"
                }
                """;

        mockMvc.perform(patch(BASIC_INFO_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());

        verify(profileService, never()).updateAdminEnterpriseBasicInfo(any(), any());
    }

    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_SYSTEM_ADMIN"})
    void admin_updateAdminEnterpriseBasicInfo_negativeEmployeeCount_returnsBadRequest() throws Exception {
        String json = """
                {
                    "employeeCount": -10
                }
                """;

        mockMvc.perform(patch(BASIC_INFO_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());

        verify(profileService, never()).updateAdminEnterpriseBasicInfo(any(), any());
    }

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_BUSINESS_DEVELOPMENT_STAFF"})
    void staff_getAdminMyEnterprise_returnsForbidden() throws Exception {
        mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isForbidden());

        verify(profileService, never()).getProfileByCompanyId(any());
    }

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_BUSINESS_DEVELOPMENT_STAFF"})
    void staff_updateAdminEnterpriseBasicInfo_returnsForbidden() throws Exception {
        mockMvc.perform(patch(BASIC_INFO_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"legalName\":\"Attempted\"}"))
                .andExpect(status().isForbidden());

        verify(profileService, never()).updateAdminEnterpriseBasicInfo(any(), any());
    }

    @Test
    @WithMockUser(username = "manager", authorities = {"ROLE_BUSINESS_DEVELOPMENT_MANAGER"})
    void manager_getAdminMyEnterprise_returnsForbidden() throws Exception {
        mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isForbidden());

        verify(profileService, never()).getProfileByCompanyId(any());
    }

    @Test
    @WithMockUser(username = "manager", authorities = {"ROLE_BUSINESS_DEVELOPMENT_MANAGER"})
    void manager_updateAdminEnterpriseBasicInfo_returnsForbidden() throws Exception {
        mockMvc.perform(patch(BASIC_INFO_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"legalName\":\"Attempted\"}"))
                .andExpect(status().isForbidden());

        verify(profileService, never()).updateAdminEnterpriseBasicInfo(any(), any());
    }

    @Test
    @WithMockUser(username = "owner", authorities = {"ROLE_BUSINESS_OWNER"})
    void owner_getAdminMyEnterprise_returnsForbidden() throws Exception {
        mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isForbidden());

        verify(profileService, never()).getProfileByCompanyId(any());
    }

    @Test
    @WithMockUser(username = "owner", authorities = {"ROLE_BUSINESS_OWNER"})
    void owner_updateAdminEnterpriseBasicInfo_returnsForbidden() throws Exception {
        mockMvc.perform(patch(BASIC_INFO_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"legalName\":\"Attempted\"}"))
                .andExpect(status().isForbidden());

        verify(profileService, never()).updateAdminEnterpriseBasicInfo(any(), any());
    }

    @Test
    void unauthenticated_getAdminMyEnterprise_returnsUnauthorized() throws Exception {
        mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_SYSTEM_ADMIN"})
    void admin_updateAdminEnterpriseBusinessFields_returnsOk() throws Exception {
        when(profileService.updateAdminEnterpriseBusinessFields(any(), any()))
                .thenReturn(ProfileResponse.builder()
                        .companyId("owner-fpt-id")
                        .build());

        String json = """
                {
                    "industries": ["Information Technology"],
                    "markets": ["Vietnam", "Global"],
                    "targetCustomers": ["Enterprise"],
                    "products": [
                        {
                            "name": "Cloud Suite",
                            "category": "Cloud Computing",
                            "description": "Enterprise cloud platform"
                        }
                    ],
                    "expectedMajorVersion": 1,
                    "expectedRevision": 0
                }
                """;

        mockMvc.perform(patch(BUSINESS_FIELDS_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());

        verify(profileService).updateAdminEnterpriseBusinessFields(any(), any());
    }

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_BUSINESS_DEVELOPMENT_STAFF"})
    void staff_updateAdminEnterpriseBusinessFields_returnsForbidden() throws Exception {
        mockMvc.perform(patch(BUSINESS_FIELDS_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"industries\":[\"IT\"]}"))
                .andExpect(status().isForbidden());

        verify(profileService, never()).updateAdminEnterpriseBusinessFields(any(), any());
    }

    @Test
    @WithMockUser(username = "manager", authorities = {"ROLE_BUSINESS_DEVELOPMENT_MANAGER"})
    void manager_updateAdminEnterpriseBusinessFields_returnsForbidden() throws Exception {
        mockMvc.perform(patch(BUSINESS_FIELDS_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"industries\":[\"IT\"]}"))
                .andExpect(status().isForbidden());

        verify(profileService, never()).updateAdminEnterpriseBusinessFields(any(), any());
    }

    @Test
    @WithMockUser(username = "owner", authorities = {"ROLE_BUSINESS_OWNER"})
    void owner_updateAdminEnterpriseBusinessFields_returnsForbidden() throws Exception {
        mockMvc.perform(patch(BUSINESS_FIELDS_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"industries\":[\"IT\"]}"))
                .andExpect(status().isForbidden());

        verify(profileService, never()).updateAdminEnterpriseBusinessFields(any(), any());
    }

    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_SYSTEM_ADMIN"})
    void admin_updateAdminEnterpriseLeadership_returnsOk() throws Exception {
        when(profileService.updateAdminEnterpriseLeadership(any(), any()))
                .thenReturn(ProfileResponse.builder()
                        .companyId("owner-fpt-id")
                        .build());

        String json = """
                {
                    "members": [
                        {
                            "fullName": "Truong Gia Binh",
                            "position": "Chairman",
                            "imageUrl": "https://example.com/binh.jpg",
                            "sourceUrl": "https://fpt.com.vn",
                            "notes": "Founder"
                        }
                    ],
                    "expectedMajorVersion": 1,
                    "expectedRevision": 0
                }
                """;

        mockMvc.perform(put(LEADERSHIP_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());

        verify(profileService).updateAdminEnterpriseLeadership(any(), any());
    }

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_BUSINESS_DEVELOPMENT_STAFF"})
    void staff_updateAdminEnterpriseLeadership_returnsForbidden() throws Exception {
        mockMvc.perform(put(LEADERSHIP_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"members\":[]}"))
                .andExpect(status().isForbidden());

        verify(profileService, never()).updateAdminEnterpriseLeadership(any(), any());
    }

    @Test
    @WithMockUser(username = "manager", authorities = {"ROLE_BUSINESS_DEVELOPMENT_MANAGER"})
    void manager_updateAdminEnterpriseLeadership_returnsForbidden() throws Exception {
        mockMvc.perform(put(LEADERSHIP_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"members\":[]}"))
                .andExpect(status().isForbidden());

        verify(profileService, never()).updateAdminEnterpriseLeadership(any(), any());
    }

    @Test
    @WithMockUser(username = "owner", authorities = {"ROLE_BUSINESS_OWNER"})
    void owner_updateAdminEnterpriseLeadership_returnsForbidden() throws Exception {
        mockMvc.perform(put(LEADERSHIP_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"members\":[]}"))
                .andExpect(status().isForbidden());

        verify(profileService, never()).updateAdminEnterpriseLeadership(any(), any());
    }
}
