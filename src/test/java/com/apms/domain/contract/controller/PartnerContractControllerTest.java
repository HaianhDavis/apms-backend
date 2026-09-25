//package com.apms.domain.contract.controller;
//
//import com.apms.domain.contract.dto.*;
//import com.apms.domain.contract.service.PartnerContractService;
//import com.apms.security.UserDetailsImpl;
//import com.fasterxml.jackson.databind.ObjectMapper;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.InjectMocks;
//import org.mockito.Mock;
//import org.mockito.junit.jupiter.MockitoExtension;
//import org.springframework.core.MethodParameter;
//import org.springframework.http.MediaType;
//import org.springframework.security.access.prepost.PreAuthorize;
//import org.springframework.test.web.servlet.MockMvc;
//import org.springframework.test.web.servlet.setup.MockMvcBuilders;
//import org.springframework.web.bind.support.WebDataBinderFactory;
//import org.springframework.web.context.request.NativeWebRequest;
//import org.springframework.web.method.support.HandlerMethodArgumentResolver;
//import org.springframework.web.method.support.ModelAndViewContainer;
//
//import java.util.List;
//
//import static org.mockito.ArgumentMatchers.any;
//import static org.mockito.ArgumentMatchers.eq;
//import static org.mockito.Mockito.when;
//import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
//import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
//import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
//
//@ExtendWith(MockitoExtension.class)
//class PartnerContractControllerTest {
//
//    private MockMvc mockMvc;
//
//    @Mock
//    private PartnerContractService service;
//
//    @InjectMocks
//    private PartnerContractController controller;
//
//    private ObjectMapper objectMapper = new ObjectMapper();
//
//    private UserDetailsImpl currentPrincipal;
//
//    @BeforeEach
//    void setUp() {
//        currentPrincipal = new UserDetailsImpl(99L, "staff@test.com", "pass", List.of(), true);
//
//        HandlerMethodArgumentResolver principalResolver = new HandlerMethodArgumentResolver() {
//            @Override
//            public boolean supportsParameter(MethodParameter parameter) {
//                return parameter.getParameterType().equals(UserDetailsImpl.class);
//            }
//
//            @Override
//            public Object resolveArgument(MethodParameter parameter,
//                                          ModelAndViewContainer mavContainer,
//                                          NativeWebRequest webRequest,
//                                          WebDataBinderFactory binderFactory) {
//                return currentPrincipal;
//            }
//        };
//
//        mockMvc = MockMvcBuilders.standaloneSetup(controller)
//                .setCustomArgumentResolvers(principalResolver)
//                .build();
//    }
//
//    // ─────────────────────────────────────────────
//    // Route Tests
//    // ─────────────────────────────────────────────
//
//    @Test
//    void testCreateDraft() throws Exception {
//        CreatePartnerContractRequest request = new CreatePartnerContractRequest();
//        request.setContractNumber("CN-123");
//
//        PartnerContractResponse mockResponse = PartnerContractResponse.builder().id(1L).contractNumber("CN-123").build();
//        when(service.createDraft(eq(10L), any(), eq(99L))).thenReturn(mockResponse);
//
//        mockMvc.perform(post("/api/v1/projects/10/partner-contracts")
//                        .contentType(MediaType.APPLICATION_JSON)
//                        .content(objectMapper.writeValueAsString(request)))
//                .andExpect(status().isCreated())
//                .andExpect(jsonPath("$.id").value(1));
//    }
//
//    @Test
//    void testGetContract() throws Exception {
//        PartnerContractResponse mockResponse = PartnerContractResponse.builder().id(1L).build();
//        when(service.getContract(1L, 99L)).thenReturn(mockResponse);
//
//        mockMvc.perform(get("/api/v1/partner-contracts/1"))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.id").value(1));
//    }
//
//    @Test
//    void testUpdateContract() throws Exception {
//        UpdatePartnerContractRequest request = new UpdatePartnerContractRequest();
//        request.setContractTitle("New Title");
//
//        PartnerContractResponse mockResponse = PartnerContractResponse.builder().id(1L).contractTitle("New Title").build();
//        when(service.updateContract(eq(1L), any(), eq(99L))).thenReturn(mockResponse);
//
//        mockMvc.perform(patch("/api/v1/partner-contracts/1")
//                        .contentType(MediaType.APPLICATION_JSON)
//                        .content(objectMapper.writeValueAsString(request)))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.id").value(1))
//                .andExpect(jsonPath("$.contractTitle").value("New Title"));
//    }
//
//    @Test
//    void testSubmitForReview() throws Exception {
//        PartnerContractResponse mockResponse = PartnerContractResponse.builder().id(1L).build();
//        when(service.submitForReview(1L, 99L)).thenReturn(mockResponse);
//
//        mockMvc.perform(post("/api/v1/partner-contracts/1/submit"))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.id").value(1));
//    }
//
//    @Test
//    void testReviewContract() throws Exception {
//        ReviewPartnerContractRequest request = new ReviewPartnerContractRequest();
//        request.setDecision("APPROVE");
//
//        mockMvc.perform(post("/api/v1/partner-contracts/1/review")
//                        .contentType(MediaType.APPLICATION_JSON)
//                        .content(objectMapper.writeValueAsString(request)))
//                .andExpect(status().isNoContent());
//    }
//
//    @Test
//    void testUpdateLifecycle() throws Exception {
//        UpdateContractLifecycleRequest request = new UpdateContractLifecycleRequest();
//        // Assume enums set appropriately if needed
//        PartnerContractResponse mockResponse = PartnerContractResponse.builder().id(1L).build();
//        when(service.updateLifecycle(eq(1L), any(), eq(99L))).thenReturn(mockResponse);
//
//        mockMvc.perform(post("/api/v1/partner-contracts/1/lifecycle")
//                        .contentType(MediaType.APPLICATION_JSON)
//                        .content(objectMapper.writeValueAsString(request)))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.id").value(1));
//    }
//
//    @Test
//    void testStartRevision() throws Exception {
//        PartnerContractResponse mockResponse = PartnerContractResponse.builder().id(1L).build();
//        when(service.startRevision(1L, 99L)).thenReturn(mockResponse);
//
//        mockMvc.perform(post("/api/v1/partner-contracts/1/revision"))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.id").value(1));
//    }
//
//    @Test
//    void testGetVersions() throws Exception {
//        when(service.getVersions(1L, 99L)).thenReturn(List.of(
//            PartnerContractVersionResponse.builder().id(1L).version(1).build()
//        ));
//
//        mockMvc.perform(get("/api/v1/partner-contracts/1/versions"))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$[0].version").value(1));
//    }
//
//    @Test
//    void testGetVersion() throws Exception {
//        PartnerContractVersionResponse mockResponse = PartnerContractVersionResponse.builder().id(1L).version(1).build();
//        when(service.getVersion(1L, 1, 99L)).thenReturn(mockResponse);
//
//        mockMvc.perform(get("/api/v1/partner-contracts/1/versions/1"))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.version").value(1));
//    }
//
//    @Test
//    void testMalformedPayload() throws Exception {
//        // Missing required fields mapping to JSON errors, or just unparseable
//        mockMvc.perform(post("/api/v1/projects/10/partner-contracts")
//                        .contentType(MediaType.APPLICATION_JSON)
//                        .content("{ malformed_json_here }"))
//                .andExpect(status().isBadRequest());
//    }
//
//    // ─────────────────────────────────────────────
//    // Authorization Reflection Tests
//    // (Note: The full Spring Security filter chain is not covered here,
//    // these prove the exact annotations on the Controller methods.)
//    // ─────────────────────────────────────────────
//
//    @Test
//    void testAuthorization_ReviewContract_RequiresManagerOnly() throws Exception {
//        java.lang.reflect.Method method = PartnerContractController.class.getMethod("reviewContract", Long.class, ReviewPartnerContractRequest.class, UserDetailsImpl.class);
//        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
//
//        org.junit.jupiter.api.Assertions.assertNotNull(preAuthorize, "Method must have @PreAuthorize");
//        org.junit.jupiter.api.Assertions.assertEquals("hasRole('BUSINESS_DEVELOPMENT_MANAGER')", preAuthorize.value(),
//            "Only Manager can review. Staff, System Admin, and Owner must be rejected.");
//    }
//}
