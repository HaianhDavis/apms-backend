package com.apms.domain.profile.controller;

import com.apms.common.exception.BusinessValidationException;
import com.apms.config.SecurityConfig;
import com.apms.domain.profile.dto.FinancialReportRequest;
import com.apms.domain.profile.dto.ProfileResponse;
import com.apms.domain.profile.dto.UpdateOwnerCompanyProfileRequest;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminOwnerCompanyProfileController.class)
@Import(SecurityConfig.class)
@AutoConfigureMockMvc(addFilters = true)
@EnableMethodSecurity
class AdminOwnerCompanyProfileControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProfileService profileService;

    @MockitoBean
    private OwnerOrganizationService ownerOrganizationService;

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

    private static final String ENDPOINT = "/api/v1/admin/owner-company-profile";

    // TEST 1 (HTTP): Admin GET owner profile → 200
    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_SYSTEM_ADMIN"})
    void admin_getOwnerCompanyProfile_returnsOk() throws Exception {
        when(ownerOrganizationService.getOwnerCompanyId()).thenReturn("owner-company-1");
        when(profileService.getProfileByCompanyId("owner-company-1"))
                .thenReturn(ProfileResponse.builder().companyId("owner-company-1").build());

        mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isOk());

        verify(profileService).getProfileByCompanyId("owner-company-1");
    }

    // TEST 4 (HTTP): Admin PATCH owner profile hợp lệ → 200, service nhận whitelist DTO
    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_SYSTEM_ADMIN"})
    void admin_updateOwnerCompanyProfile_returnsOk() throws Exception {
        when(profileService.updateOwnerCompanyProfile(any()))
                .thenReturn(ProfileResponse.builder().companyId("owner-company-1").build());

        mockMvc.perform(patch(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"legalName\":\"FPT Corporation\",\"email\":\"contact@fpt.com\",\"website\":\"https://fpt.com\",\"tags\":[\"tech\"]}"))
                .andExpect(status().isOk());

        ArgumentCaptor<UpdateOwnerCompanyProfileRequest> captor =
                ArgumentCaptor.forClass(UpdateOwnerCompanyProfileRequest.class);
        verify(profileService).updateOwnerCompanyProfile(captor.capture());
        assertThat(captor.getValue().getLegalName()).isEqualTo("FPT Corporation");
        assertThat(captor.getValue().getEmail()).isEqualTo("contact@fpt.com");
        assertThat(captor.getValue().getWebsite()).isEqualTo("https://fpt.com");
        assertThat(captor.getValue().getTags()).containsExactly("tech");
    }

    // TEST 11 (HTTP): Admin PATCH đủ 4 module (Overview + listing, SWOT, Business Fields, Leadership) → 200 + whitelist binding
    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_SYSTEM_ADMIN"})
    void admin_updateOwnerCompanyProfile_fullModules_returnsOk() throws Exception {
        when(profileService.updateOwnerCompanyProfile(any()))
                .thenReturn(ProfileResponse.builder().companyId("owner-company-1").build());

        mockMvc.perform(patch(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "legalName": "FPT Corporation",
                                  "stockTicker": "FPT",
                                  "stockExchange": "HOSE",
                                  "insights": {
                                    "strengths": ["Strong technology capability"],
                                    "threats": ["Intense competition"]
                                  },
                                  "products": [
                                    { "name": "Software Outsourcing", "category": "IT Services", "description": "Custom development" }
                                  ],
                                  "targetCustomers": ["Banking", "Healthcare"],
                                  "companyMembers": [
                                    { "fullName": "Truong Gia Binh", "position": "Chairman", "notes": "Co-founder" }
                                  ]
                                }
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<UpdateOwnerCompanyProfileRequest> captor =
                ArgumentCaptor.forClass(UpdateOwnerCompanyProfileRequest.class);
        verify(profileService).updateOwnerCompanyProfile(captor.capture());
        UpdateOwnerCompanyProfileRequest value = captor.getValue();
        assertThat(value.getLegalName()).isEqualTo("FPT Corporation");
        assertThat(value.getStockTicker()).isEqualTo("FPT");
        assertThat(value.getStockExchange()).isEqualTo("HOSE");
        assertThat(value.getInsights().getStrengths()).containsExactly("Strong technology capability");
        assertThat(value.getInsights().getThreats()).containsExactly("Intense competition");
        assertThat(value.getProducts()).hasSize(1);
        assertThat(value.getProducts().get(0).getName()).isEqualTo("Software Outsourcing");
        assertThat(value.getProducts().get(0).getCategory()).isEqualTo("IT Services");
        assertThat(value.getTargetCustomers()).containsExactly("Banking", "Healthcare");
        assertThat(value.getCompanyMembers()).hasSize(1);
        assertThat(value.getCompanyMembers().get(0).getFullName()).isEqualTo("Truong Gia Binh");
        assertThat(value.getCompanyMembers().get(0).getPosition()).isEqualTo("Chairman");
    }

    // TEST 12 (HTTP): Admin PATCH giữ nguyên 4 module → 200 (field-wise, không ép null)
    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_SYSTEM_ADMIN"})
    void admin_updateOwnerCompanyProfile_partialModules_returnsOk() throws Exception {
        when(profileService.updateOwnerCompanyProfile(any()))
                .thenReturn(ProfileResponse.builder().companyId("owner-company-1").build());

        mockMvc.perform(patch(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "legalName": "FPT Corporation",
                                  "insights": { "weaknesses": ["Need more talent"] },
                                  "targetCustomers": ["Education"]
                                }
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<UpdateOwnerCompanyProfileRequest> captor =
                ArgumentCaptor.forClass(UpdateOwnerCompanyProfileRequest.class);
        verify(profileService).updateOwnerCompanyProfile(captor.capture());
        assertThat(captor.getValue().getInsights().getWeaknesses()).containsExactly("Need more talent");
        assertThat(captor.getValue().getInsights().getStrengths()).isNull();
        assertThat(captor.getValue().getTargetCustomers()).containsExactly("Education");
        assertThat(captor.getValue().getProducts()).isNull();
        assertThat(captor.getValue().getCompanyMembers()).isNull();
    }

    // TEST 8 (HTTP): Admin gửi kèm companyId/id trong body → bị bỏ qua (whitelist DTO, backend tự resolve owner)
    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_SYSTEM_ADMIN"})
    void admin_updateOwnerCompanyProfile_extraCompanyIdInBodyIsIgnored() throws Exception {
        when(profileService.updateOwnerCompanyProfile(any()))
                .thenReturn(ProfileResponse.builder().companyId("owner-company-1").build());

        mockMvc.perform(patch(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"legalName\":\"FPT Corporation\",\"companyId\":\"OTHER-COMPANY\",\"id\":\"other-doc\",\"reviewStatus\":\"APPROVED\",\"createdAt\":\"2020-01-01\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<UpdateOwnerCompanyProfileRequest> captor =
                ArgumentCaptor.forClass(UpdateOwnerCompanyProfileRequest.class);
        verify(profileService).updateOwnerCompanyProfile(captor.capture());
        assertThat(captor.getValue().getLegalName()).isEqualTo("FPT Corporation");
    }

    // TEST 10 (HTTP): Dữ liệu không hợp lệ → 400, không gọi service (không update DB)
    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_SYSTEM_ADMIN"})
    void admin_updateOwnerCompanyProfile_invalidBody_returnsBadRequest_serviceNotCalled() throws Exception {
        mockMvc.perform(patch(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"legalName\":\"\",\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest());

        verify(profileService, never()).updateOwnerCompanyProfile(any());
    }

    // TEST 13 (HTTP): Admin PATCH chỉ gửi một phần (SWOT), không kèm legalName → 200 (field-wise update)
    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_SYSTEM_ADMIN"})
    void admin_updateOwnerCompanyProfile_sectionOnly_returnsOk() throws Exception {
        when(profileService.updateOwnerCompanyProfile(any()))
                .thenReturn(ProfileResponse.builder().companyId("owner-company-1").build());

        mockMvc.perform(patch(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "insights": { "strengths": ["Đội ngũ mạnh"] } }
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<UpdateOwnerCompanyProfileRequest> captor =
                ArgumentCaptor.forClass(UpdateOwnerCompanyProfileRequest.class);
        verify(profileService).updateOwnerCompanyProfile(captor.capture());
        assertThat(captor.getValue().getLegalName()).isNull();
        assertThat(captor.getValue().getInsights().getStrengths()).containsExactly("Đội ngũ mạnh");
        assertThat(captor.getValue().getCompanyMembers()).isNull();
    }

    // TEST 5 (HTTP): Staff PATCH owner profile → 403
    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_BUSINESS_DEVELOPMENT_STAFF"})
    void staff_updateOwnerCompanyProfile_returnsForbidden() throws Exception {
        mockMvc.perform(patch(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"legalName\":\"FPT Corporation\"}"))
                .andExpect(status().isForbidden());

        verify(profileService, never()).updateOwnerCompanyProfile(any());
    }

    // TEST 6 (HTTP): Manager PATCH owner profile → 403
    @Test
    @WithMockUser(username = "manager", authorities = {"ROLE_BUSINESS_DEVELOPMENT_MANAGER"})
    void manager_updateOwnerCompanyProfile_returnsForbidden() throws Exception {
        mockMvc.perform(patch(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"legalName\":\"FPT Corporation\"}"))
                .andExpect(status().isForbidden());

        verify(profileService, never()).updateOwnerCompanyProfile(any());
    }

    // TEST 7 (HTTP): Owner PATCH owner profile → 403
    @Test
    @WithMockUser(username = "owner", authorities = {"ROLE_BUSINESS_OWNER"})
    void owner_updateOwnerCompanyProfile_returnsForbidden() throws Exception {
        mockMvc.perform(patch(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"legalName\":\"FPT Corporation\"}"))
                .andExpect(status().isForbidden());

        verify(profileService, never()).updateOwnerCompanyProfile(any());
    }

    // TEST 2 (HTTP): Chưa đăng nhập → 401
    @Test
    void unauthenticated_getOwnerCompanyProfile_returnsUnauthorized() throws Exception {
        mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isUnauthorized());
    }

    // TEST 3 (HTTP): Owner profile không tồn tại → 400 từ service (backend tự resolve, không thể chọn công ty khác)
    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_SYSTEM_ADMIN"})
    void admin_getOwnerCompanyProfile_ownerMissing_returnsBadRequest() throws Exception {
        when(ownerOrganizationService.getOwnerCompanyId()).thenReturn("missing-owner");
        when(profileService.getProfileByCompanyId("missing-owner"))
                .thenThrow(new BusinessValidationException("Owner CompanyProfile not found for ID: missing-owner"));

        mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isBadRequest());
    }

    private static final String FINANCIALS_ENDPOINT = ENDPOINT + "/financials";
    private static final String ITEMS_JSON =
            "{\"unit\":\"Tỷ đồng\",\"templace\":[{\"code\":\"REVENUE\",\"name\":\"Doanh thu\"}]," +
            "\"data\":[{\"data\":[{\"time\":\"2025\",\"data\":[{\"code\":\"REVENUE\",\"value\":15000}]}]}]}";

    // TEST (HTTP): Admin PUT financials hợp lệ → 200, service nhận (reportType, reportYear) đúng
    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_SYSTEM_ADMIN"})
    void admin_upsertOwnerFinancialReport_returnsOk() throws Exception {
        when(profileService.upsertOwnerFinancialReport(any()))
                .thenReturn(ProfileResponse.builder().companyId("owner-company-1").build());

        mockMvc.perform(put(FINANCIALS_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportType\":\"SUMMARY\",\"reportYear\":2025,\"periodType\":\"YEAR\",\"itemsJson\":\"" + ITEMS_JSON.replace("\"", "\\\"") + "\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<FinancialReportRequest> captor = ArgumentCaptor.forClass(FinancialReportRequest.class);
        verify(profileService).upsertOwnerFinancialReport(captor.capture());
        assertThat(captor.getValue().getReportType()).isEqualTo("SUMMARY");
        assertThat(captor.getValue().getReportYear()).isEqualTo(2025);
        assertThat(captor.getValue().getItemsJson()).contains("\"time\":\"2025\"");
    }

    // TEST (HTTP): Admin PUT financials body không hợp lệ → 400, không gọi service
    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_SYSTEM_ADMIN"})
    void admin_upsertOwnerFinancialReport_invalidBody_returnsBadRequest_serviceNotCalled() throws Exception {
        mockMvc.perform(put(FINANCIALS_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportType\":\"\",\"reportYear\":3000}"))
                .andExpect(status().isBadRequest());

        verify(profileService, never()).upsertOwnerFinancialReport(any());
    }

    // TEST (HTTP): Admin DELETE financials → 200, service nhận đúng reportType + reportYear
    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_SYSTEM_ADMIN"})
    void admin_deleteOwnerFinancialReport_returnsOk() throws Exception {
        when(profileService.deleteOwnerFinancialReport("SUMMARY", 2025))
                .thenReturn(ProfileResponse.builder().companyId("owner-company-1").build());

        mockMvc.perform(delete(FINANCIALS_ENDPOINT + "/SUMMARY/2025"))
                .andExpect(status().isOk());

        verify(profileService).deleteOwnerFinancialReport("SUMMARY", 2025);
    }

    // TEST (HTTP): Staff PUT financials → 403, không gọi service (chỉ SYSTEM_ADMIN được ghi)
    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_BUSINESS_DEVELOPMENT_STAFF"})
    void staff_upsertOwnerFinancialReport_returnsForbidden() throws Exception {
        mockMvc.perform(put(FINANCIALS_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportType\":\"SUMMARY\",\"reportYear\":2025,\"itemsJson\":\"{}\"}"))
                .andExpect(status().isForbidden());

        verify(profileService, never()).upsertOwnerFinancialReport(any());
    }

    // TEST (HTTP): Owner DELETE financials → 403, không gọi service
    @Test
    @WithMockUser(username = "owner", authorities = {"ROLE_BUSINESS_OWNER"})
    void owner_deleteOwnerFinancialReport_returnsForbidden() throws Exception {
        mockMvc.perform(delete(FINANCIALS_ENDPOINT + "/SUMMARY/2025"))
                .andExpect(status().isForbidden());

        verify(profileService, never()).deleteOwnerFinancialReport(anyString(), anyInt());
    }
}
