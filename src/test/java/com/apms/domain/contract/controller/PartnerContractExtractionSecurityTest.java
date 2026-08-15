//package com.apms.domain.contract.controller;
//
//import com.apms.config.SecurityConfig;
//import com.apms.common.exception.ResourceNotFoundException;
//import com.apms.domain.contract.dto.ApplyExtractionRequest;
//import com.apms.domain.contract.dto.ReviewExtractionClauseRequest;
//import com.apms.domain.contract.dto.ReviewExtractionFieldRequest;
//import com.apms.domain.contract.enums.ContractExtractionReviewDecision;
//import com.apms.domain.contract.service.PartnerContractExtractionService;
//import com.apms.domain.contract.service.PartnerContractService;
//import com.apms.security.UserDetailsImpl;
//import com.apms.security.UserDetailsServiceImpl;
//import com.apms.security.AuthEntryPointJwt;
//import com.apms.security.AuthTokenFilter;
//import com.apms.security.JwtUtils;
//import com.apms.common.security.ProjectSecurityEvaluator;
//import com.fasterxml.jackson.databind.ObjectMapper;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
//import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
//import org.springframework.context.annotation.Import;
//import org.springframework.http.MediaType;
//import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
//import org.springframework.security.core.authority.SimpleGrantedAuthority;
//import org.springframework.test.context.bean.override.mockito.MockitoBean;
//import org.springframework.test.web.servlet.MockMvc;
//import jakarta.servlet.FilterChain;
//
//import java.util.List;
//
//import static org.mockito.ArgumentMatchers.*;
//import static org.mockito.Mockito.doAnswer;
//import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
//import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
//import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
//import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
//
//@WebMvcTest(PartnerContractExtractionController.class)
//@Import(SecurityConfig.class)
//@AutoConfigureMockMvc(addFilters = true)
//@EnableMethodSecurity
//public class PartnerContractExtractionSecurityTest {
//
//    @Autowired
//    private MockMvc mockMvc;
//
//    @Autowired
//    private ObjectMapper objectMapper;
//
//    @MockitoBean
//    private PartnerContractExtractionService extractionService;
//
//    @MockitoBean
//    private PartnerContractService partnerContractService;
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
//    @MockitoBean
//    private ProjectSecurityEvaluator projectSecurity;
//
//    private final Long contractId = 1L;
//    private final String extractionId = "ext-api-1";
//
//    @BeforeEach
//    void setup() throws Exception {
//        doAnswer(invocation -> {
//            jakarta.servlet.http.HttpServletResponse response = invocation.getArgument(1);
//            response.sendError(jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED, "Error: Unauthorized");
//            return null;
//        }).when(unauthorizedHandler).commence(any(), any(), any());
//
//        // Mock services to throw ResourceNotFoundException to get 404
//        doAnswer(inv -> { throw new ResourceNotFoundException("Not found"); })
//                .when(extractionService).reviewField(anyLong(), anyString(), anyString(), any(), anyLong());
//
//        doAnswer(inv -> { throw new ResourceNotFoundException("Not found"); })
//                .when(extractionService).reviewClause(anyLong(), anyString(), anyString(), any(), anyLong());
//
//        doAnswer(inv -> { throw new ResourceNotFoundException("Not found"); })
//                .when(extractionService).applyExtraction(anyLong(), anyString(), any(), anyLong());
//
//        doAnswer(inv -> { throw new ResourceNotFoundException("Not found"); })
//                .when(extractionService).listExtractions(anyLong(), anyLong());
//
//        doAnswer(inv -> { throw new ResourceNotFoundException("Not found"); })
//                .when(extractionService).getExtraction(anyLong(), anyString(), anyLong());
//
//        doAnswer(inv -> { throw new ResourceNotFoundException("Not found"); })
//                .when(partnerContractService).getApprovedClauses(anyLong(), anyInt(), anyLong());
//
//        doAnswer(inv -> { throw new ResourceNotFoundException("Not found"); })
//                .when(partnerContractService).getApprovedClause(anyLong(), anyInt(), anyString(), anyLong());
//
//        doAnswer(invocation -> {
//            FilterChain chain = invocation.getArgument(2);
//            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
//            return null;
//        }).when(authTokenFilter).doFilter(any(), any(), any());
//    }
//
//    private UserDetailsImpl manager() {
//        return new UserDetailsImpl(1L, "mgr", "pwd", List.of(new SimpleGrantedAuthority("ROLE_BUSINESS_DEVELOPMENT_MANAGER")), true);
//    }
//
//    private UserDetailsImpl staff() {
//        return new UserDetailsImpl(2L, "stf", "pwd", List.of(new SimpleGrantedAuthority("ROLE_BUSINESS_DEVELOPMENT_STAFF")), true);
//    }
//
//    private UserDetailsImpl admin() {
//        return new UserDetailsImpl(3L, "adm", "pwd", List.of(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")), true);
//    }
//
//    private UserDetailsImpl owner() {
//        return new UserDetailsImpl(4L, "own", "pwd", List.of(new SimpleGrantedAuthority("ROLE_BUSINESS_OWNER")), true);
//    }
//
//    @Test
//    void shouldReturnBadRequestForMalformedPayload() throws Exception {
//        // Kept to show 400 maps successfully in this security test too if needed, but not required here anymore.
//        // I will keep it for compatibility with the prior requirement, though controller test does it now.
//        String malformedJson = "{ malformed json }";
//
//        mockMvc.perform(patch("/api/v1/partner-contracts/" + contractId + "/extractions/" + extractionId + "/fields/someKey")
//                .with(user(manager())).with(csrf())
//                .contentType(MediaType.APPLICATION_JSON)
//                .content(malformedJson))
//                .andExpect(status().isBadRequest());
//    }
//
//    @Test
//    void shouldAcceptTypedDtoForFieldReviewByStaff() throws Exception {
//        ReviewExtractionFieldRequest req = new ReviewExtractionFieldRequest();
//        req.setReviewDecision(ContractExtractionReviewDecision.ACCEPT);
//
//        mockMvc.perform(patch("/api/v1/partner-contracts/" + contractId + "/extractions/" + extractionId + "/fields/someKey")
//                .with(user(staff())).with(csrf())
//                .contentType(MediaType.APPLICATION_JSON)
//                .content(objectMapper.writeValueAsString(req)))
//                .andExpect(status().isNotFound()); // NotFound implies it passed security and hit the mocked service
//    }
//
//    @Test
//    void shouldDenyOwnerMutation() throws Exception {
//        ReviewExtractionFieldRequest req = new ReviewExtractionFieldRequest();
//        req.setReviewDecision(ContractExtractionReviewDecision.ACCEPT);
//
//        mockMvc.perform(patch("/api/v1/partner-contracts/" + contractId + "/extractions/" + extractionId + "/fields/someKey")
//                .with(user(owner())).with(csrf())
//                .contentType(MediaType.APPLICATION_JSON)
//                .content(objectMapper.writeValueAsString(req)))
//                .andExpect(status().isForbidden());
//    }
//
//    @Test
//    void shouldDenySystemAdminMutation() throws Exception {
//        ReviewExtractionFieldRequest req = new ReviewExtractionFieldRequest();
//        req.setReviewDecision(ContractExtractionReviewDecision.ACCEPT);
//
//        mockMvc.perform(patch("/api/v1/partner-contracts/" + contractId + "/extractions/" + extractionId + "/fields/someKey")
//                .with(user(admin())).with(csrf())
//                .contentType(MediaType.APPLICATION_JSON)
//                .content(objectMapper.writeValueAsString(req)))
//                .andExpect(status().isForbidden());
//    }
//
//    @Test
//    void shouldAllowOwnerToReadApprovedClauses() throws Exception {
//        mockMvc.perform(get("/api/v1/partner-contracts/" + contractId + "/versions/1/clauses").with(user(owner())))
//                .andExpect(status().isNotFound()); // Passed security
//
//        mockMvc.perform(get("/api/v1/partner-contracts/" + contractId + "/versions/1/clauses/999").with(user(owner())))
//                .andExpect(status().isNotFound()); // Passed security
//    }
//
//    @Test
//    void shouldDenyUnauthenticated() throws Exception {
//        mockMvc.perform(get("/api/v1/partner-contracts/" + contractId + "/extractions"))
//                .andExpect(status().isUnauthorized());
//    }
//}
