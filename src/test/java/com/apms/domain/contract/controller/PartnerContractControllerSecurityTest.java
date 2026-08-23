package com.apms.domain.contract.controller;

import com.apms.config.SecurityConfig;
import com.apms.domain.contract.dto.ReviewPartnerContractRequest;
import com.apms.domain.contract.dto.UpdateContractLifecycleRequest;
import com.apms.domain.contract.service.PartnerContractService;
import com.apms.security.AuthEntryPointJwt;
import com.apms.security.AuthTokenFilter;
import com.apms.security.JwtUtils;
import com.apms.security.UserDetailsImpl;
import com.apms.security.UserDetailsServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PartnerContractController.class)
@Import(SecurityConfig.class)
@AutoConfigureMockMvc(addFilters = true)
@EnableMethodSecurity
class PartnerContractControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PartnerContractService service;

    @MockitoBean
    private UserDetailsServiceImpl userDetailsService;

    @MockitoBean
    private AuthEntryPointJwt unauthorizedHandler;

    @MockitoBean
    private AuthTokenFilter authTokenFilter;

    @MockitoBean
    private JwtUtils jwtUtils;

    private ObjectMapper objectMapper = new ObjectMapper();

    private UserDetailsImpl manager() {
        return new UserDetailsImpl(1L, "mgr", "pwd", List.of(new SimpleGrantedAuthority("ROLE_BUSINESS_DEVELOPMENT_MANAGER")), true);
    }

    private UserDetailsImpl staff() {
        return new UserDetailsImpl(2L, "stf", "pwd", List.of(new SimpleGrantedAuthority("ROLE_BUSINESS_DEVELOPMENT_STAFF")), true);
    }

    private UserDetailsImpl admin() {
        return new UserDetailsImpl(3L, "adm", "pwd", List.of(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")), true);
    }

    private UserDetailsImpl owner() {
        return new UserDetailsImpl(4L, "own", "pwd", List.of(new SimpleGrantedAuthority("ROLE_BUSINESS_OWNER")), true);
    }

    @BeforeEach
    void setUp() throws Exception {
        doAnswer(invocation -> {
            HttpServletResponse response = invocation.getArgument(1);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Error: Unauthorized");
            return null;
        }).when(unauthorizedHandler).commence(any(), any(), any());

        doAnswer(invocation -> {
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(authTokenFilter).doFilter(any(), any(), any());
    }

    @Test
    void testReview_ManagerAllowed() throws Exception {
        ReviewPartnerContractRequest req = new ReviewPartnerContractRequest();
        req.setDecision("APPROVE");
        mockMvc.perform(post("/api/v1/partner-contracts/1/review")
                        .with(user(manager())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNoContent());
    }

    @Test
    void testReview_StaffForbidden() throws Exception {
        ReviewPartnerContractRequest req = new ReviewPartnerContractRequest();
        req.setDecision("APPROVE");
        mockMvc.perform(post("/api/v1/partner-contracts/1/review")
                        .with(user(staff())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    void testReview_AdminForbidden() throws Exception {
        ReviewPartnerContractRequest req = new ReviewPartnerContractRequest();
        req.setDecision("APPROVE");
        mockMvc.perform(post("/api/v1/partner-contracts/1/review")
                        .with(user(admin())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    void testReview_OwnerForbidden() throws Exception {
        ReviewPartnerContractRequest req = new ReviewPartnerContractRequest();
        req.setDecision("APPROVE");
        mockMvc.perform(post("/api/v1/partner-contracts/1/review")
                        .with(user(owner())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    void testReview_UnauthenticatedUnauthorized() throws Exception {
        ReviewPartnerContractRequest req = new ReviewPartnerContractRequest();
        req.setDecision("APPROVE");
        mockMvc.perform(post("/api/v1/partner-contracts/1/review").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    void testLifecycle_ManagerAllowed() throws Exception {
        mockMvc.perform(post("/api/v1/partner-contracts/1/lifecycle")
                        .with(user(manager())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateContractLifecycleRequest())))
                .andExpect(status().isOk());
    }

    @Test
    void testLifecycle_StaffForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/partner-contracts/1/lifecycle")
                        .with(user(staff())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateContractLifecycleRequest())))
                .andExpect(status().isForbidden());
    }

    @Test
    void testRevision_ManagerAllowed() throws Exception {
        mockMvc.perform(post("/api/v1/partner-contracts/1/revision")
                        .with(user(manager())).with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    void testRevision_StaffForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/partner-contracts/1/revision")
                        .with(user(staff())).with(csrf()))
                .andExpect(status().isForbidden());
    }
}
