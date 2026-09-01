//package com.apms.domain.profile.controller;
//
//import com.apms.common.security.StaffCompanyScopeEvaluator;
//import com.apms.config.SecurityConfig;
//import com.apms.domain.profile.dto.ProfileResponse;
//import com.apms.domain.profile.dto.ProfileSourcesResponse;
//import com.apms.domain.profile.service.ProfileService;
//import com.apms.security.AuthEntryPointJwt;
//import com.apms.security.AuthTokenFilter;
//import com.apms.security.JwtUtils;
//import com.apms.security.UserDetailsServiceImpl;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
//import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
//import org.springframework.boot.test.context.TestConfiguration;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Import;
//import org.springframework.data.domain.PageImpl;
//import org.springframework.data.domain.PageRequest;
//import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
//import org.springframework.security.test.context.support.WithMockUser;
//import org.springframework.test.context.bean.override.mockito.MockitoBean;
//import org.springframework.test.web.servlet.MockMvc;
//
//import java.util.List;
//import java.util.Set;
//
//import static org.mockito.ArgumentMatchers.any;
//import static org.mockito.ArgumentMatchers.anyBoolean;
//import static org.mockito.ArgumentMatchers.anySet;
//import static org.mockito.ArgumentMatchers.eq;
//import static org.mockito.ArgumentMatchers.isNull;
//import static org.mockito.Mockito.doAnswer;
//import static org.mockito.Mockito.verify;
//import static org.mockito.Mockito.when;
//import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
//import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
//
//@WebMvcTest(ProfileController.class)
//@Import(SecurityConfig.class)
//@AutoConfigureMockMvc(addFilters = true)
//@EnableMethodSecurity
//class ProfileControllerCompanyScopeSecurityTest {
//
//    @Autowired
//    private MockMvc mockMvc;
//
//    @MockitoBean
//    private ProfileService profileService;
//
//    @MockitoBean(name = "companyScope")
//    private StaffCompanyScopeEvaluator companyScope;
//
//    @MockitoBean
//    private UserDetailsServiceImpl userDetailsService;
//
//    @MockitoBean
//    private AuthEntryPointJwt unauthorizedHandler;
//
//    @MockitoBean
//    private AuthTokenFilter authTokenFilter;
//
//    @MockitoBean
//    private JwtUtils jwtUtils;
//
//    @TestConfiguration
//    static class TestConfig {
//        @Bean
//        com.apms.common.security.ProjectSecurityEvaluator projectSecurity() {
//            return org.mockito.Mockito.mock(com.apms.common.security.ProjectSecurityEvaluator.class);
//        }
//    }
//
//    @BeforeEach
//    void setup() throws Exception {
//        doAnswer(invocation -> {
//            jakarta.servlet.FilterChain chain = invocation.getArgument(2);
//            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
//            return null;
//        }).when(authTokenFilter).doFilter(any(), any(), any());
//
//        doAnswer(invocation -> {
//            jakarta.servlet.http.HttpServletResponse response = invocation.getArgument(1);
//            response.sendError(jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED, "Error: Unauthorized");
//            return null;
//        }).when(unauthorizedHandler).commence(any(), any(), any());
//    }
//
//    // TEST 5 (HTTP): Staff tự sửa companyId ngoài scope → 403
//    @Test
//    @WithMockUser(username = "staff", authorities = {"ROLE_BUSINESS_DEVELOPMENT_STAFF"})
//    void staff_getProfileOutsideScope_returnsForbidden() throws Exception {
//        when(companyScope.canAccessCompany("C")).thenReturn(false);
//        mockMvc.perform(get("/api/v1/profiles/C"))
//                .andExpect(status().isForbidden());
//    }
//
//    // TEST 3 (HTTP): Staff truy cập company thuộc project được assign → 200
//    @Test
//    @WithMockUser(username = "staff", authorities = {"ROLE_BUSINESS_DEVELOPMENT_STAFF"})
//    void staff_getProfileInsideScope_returnsOk() throws Exception {
//        when(companyScope.canAccessCompany("B")).thenReturn(true);
//        when(profileService.getProfileByCompanyId("B"))
//                .thenReturn(ProfileResponse.builder().companyId("B").build());
//        mockMvc.perform(get("/api/v1/profiles/B"))
//                .andExpect(status().isOk());
//    }
//
//    // TEST 5 (HTTP): Staff truy cập sources của company ngoài scope → 403
//    @Test
//    @WithMockUser(username = "staff", authorities = {"ROLE_BUSINESS_DEVELOPMENT_STAFF"})
//    void staff_getSourcesOutsideScope_returnsForbidden() throws Exception {
//        when(companyScope.canAccessCompany("C")).thenReturn(false);
//        mockMvc.perform(get("/api/v1/profiles/C/sources"))
//                .andExpect(status().isForbidden());
//    }
//
//    // TEST 6 (HTTP): Staff list chỉ nhận danh sách company trong scope
//    @Test
//    @WithMockUser(username = "staff", authorities = {"ROLE_BUSINESS_DEVELOPMENT_STAFF"})
//    void staff_listProfiles_passesAllowedCompanyIdsToService() throws Exception {
//        Set<String> allowed = Set.of("OWNER-COMPANY", "B");
//        when(companyScope.allowedCompanyIds()).thenReturn(allowed);
//        when(profileService.searchCompanyProfiles(any(), any(), any(), any(), any(), anyBoolean(), anySet(), any()))
//                .thenReturn(new PageImpl<>(
//                        List.of(ProfileResponse.builder().companyId("B").build()),
//                        PageRequest.of(0, 20), 1));
//
//        mockMvc.perform(get("/api/v1/company-profiles"))
//                .andExpect(status().isOk());
//
//        verify(profileService).searchCompanyProfiles(
//                any(), any(), any(), any(), any(), anyBoolean(), eq(allowed), any());
//    }
//
//    // TEST 12 (HTTP): Manager list không bị giới hạn (allowedCompanyIds = null)
//    @Test
//    @WithMockUser(username = "manager", authorities = {"ROLE_BUSINESS_DEVELOPMENT_MANAGER"})
//    void manager_listProfiles_unrestricted() throws Exception {
//        when(companyScope.allowedCompanyIds()).thenReturn(null);
//        when(profileService.searchCompanyProfiles(any(), any(), any(), any(), any(), anyBoolean(), isNull(), any()))
//                .thenReturn(new PageImpl<>(
//                        List.of(ProfileResponse.builder().companyId("X").build()),
//                        PageRequest.of(0, 20), 1));
//
//        mockMvc.perform(get("/api/v1/company-profiles"))
//                .andExpect(status().isOk());
//    }
//
//    // TEST 11 (HTTP): Owner xem detail company bất kỳ → 200
//    @Test
//    @WithMockUser(username = "owner", authorities = {"ROLE_BUSINESS_OWNER"})
//    void owner_getProfileAnyCompany_returnsOk() throws Exception {
//        when(companyScope.canAccessCompany("ANY")).thenReturn(true);
//        when(profileService.getProfileByCompanyId("ANY"))
//                .thenReturn(ProfileResponse.builder().companyId("ANY").build());
//        mockMvc.perform(get("/api/v1/profiles/ANY"))
//                .andExpect(status().isOk());
//    }
//}
