package com.apms.domain.contract.controller;

import com.apms.common.exception.GlobalExceptionHandler;
import com.apms.domain.contract.dto.ApplyExtractionRequest;
import com.apms.domain.contract.dto.ReviewExtractionClauseRequest;
import com.apms.domain.contract.dto.ReviewExtractionFieldRequest;
import com.apms.domain.contract.entity.PartnerContractClauseVersion;
import com.apms.domain.contract.entity.PartnerContractExtractionDraft;
import com.apms.domain.contract.enums.ContractExtractionReviewDecision;
import com.apms.domain.contract.service.PartnerContractExtractionService;
import com.apms.domain.contract.service.PartnerContractService;
import com.apms.security.UserDetailsImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PartnerContractExtractionControllerTest {

    private MockMvc mockMvc;

    @Mock
    private PartnerContractExtractionService extractionService;

    @Mock
    private PartnerContractService partnerContractService;

    @InjectMocks
    private PartnerContractExtractionController controller;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private UserDetailsImpl currentPrincipal;

    @BeforeEach
    void setUp() {
        currentPrincipal = new UserDetailsImpl(99L, "staff@test.com", "pass", List.of(), true);

        HandlerMethodArgumentResolver principalResolver = new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.getParameterType().equals(UserDetailsImpl.class);
            }

            @Override
            public Object resolveArgument(MethodParameter parameter,
                                          ModelAndViewContainer mavContainer,
                                          NativeWebRequest webRequest,
                                          WebDataBinderFactory binderFactory) {
                return currentPrincipal;
            }
        };

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(principalResolver)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void testGenerateExtraction() throws Exception {
        PartnerContractExtractionDraft draft = PartnerContractExtractionDraft.builder().id("ext-1").build();
        when(extractionService.generateExtraction(100L, 99L)).thenReturn(draft);

        mockMvc.perform(post("/api/v1/partner-contracts/100/extractions"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("ext-1"));

        verify(extractionService).generateExtraction(100L, 99L);
    }

    @Test
    void testReviewField() throws Exception {
        PartnerContractExtractionDraft draft = PartnerContractExtractionDraft.builder().id("ext-1").build();
        when(extractionService.reviewField(eq(100L), eq("ext-1"), eq("titleKey"), any(ReviewExtractionFieldRequest.class), eq(99L))).thenReturn(draft);

        ReviewExtractionFieldRequest req = new ReviewExtractionFieldRequest();
        req.setReviewDecision(ContractExtractionReviewDecision.ACCEPT);

        mockMvc.perform(patch("/api/v1/partner-contracts/100/extractions/ext-1/fields/titleKey")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("ext-1"));
    }

    @Test
    void testReviewClause() throws Exception {
        PartnerContractExtractionDraft draft = PartnerContractExtractionDraft.builder().id("ext-1").build();
        when(extractionService.reviewClause(eq(100L), eq("ext-1"), eq("c1"), any(ReviewExtractionClauseRequest.class), eq(99L))).thenReturn(draft);

        ReviewExtractionClauseRequest req = new ReviewExtractionClauseRequest();
        req.setReviewDecision(ContractExtractionReviewDecision.ACCEPT);

        mockMvc.perform(patch("/api/v1/partner-contracts/100/extractions/ext-1/clauses/c1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("ext-1"));
    }

    @Test
    void testApplyExtraction() throws Exception {
        PartnerContractExtractionDraft draft = PartnerContractExtractionDraft.builder().id("ext-1").build();
        when(extractionService.applyExtraction(eq(100L), eq("ext-1"), any(ApplyExtractionRequest.class), eq(99L))).thenReturn(draft);

        ApplyExtractionRequest req = new ApplyExtractionRequest();
        req.setExpectedContractOptimisticVersion(2);

        mockMvc.perform(post("/api/v1/partner-contracts/100/extractions/ext-1/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("ext-1"));
    }

    @Test
    void testRegenerateExtraction() throws Exception {
        PartnerContractExtractionDraft draft = PartnerContractExtractionDraft.builder().id("ext-2").build();
        when(extractionService.regenerateExtraction(eq(100L), eq("ext-1"), eq(true), eq("Mistakes"), eq(99L))).thenReturn(draft);

        mockMvc.perform(post("/api/v1/partner-contracts/100/extractions/ext-1/regenerate")
                        .param("force", "true")
                        .param("comment", "Mistakes"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("ext-2"));
    }

    @Test
    void testListExtractions() throws Exception {
        PartnerContractExtractionDraft draft = PartnerContractExtractionDraft.builder().id("ext-1").build();
        when(extractionService.listExtractions(100L, 99L)).thenReturn(List.of(draft));

        mockMvc.perform(get("/api/v1/partner-contracts/100/extractions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("ext-1"));
    }

    @Test
    void testGetExtraction() throws Exception {
        PartnerContractExtractionDraft draft = PartnerContractExtractionDraft.builder().id("ext-1").build();
        when(extractionService.getExtraction(100L, "ext-1", 99L)).thenReturn(draft);

        mockMvc.perform(get("/api/v1/partner-contracts/100/extractions/ext-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("ext-1"));
    }

    @Test
    void testGetApprovedClauses() throws Exception {
        PartnerContractClauseVersion clause = PartnerContractClauseVersion.builder().id(1L).clauseIdentity("c1").build();
        when(partnerContractService.getApprovedClauses(100L, 1, 99L)).thenReturn(List.of(clause));

        mockMvc.perform(get("/api/v1/partner-contracts/100/versions/1/clauses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].clauseIdentity").value("c1"));
    }

    @Test
    void testGetApprovedClause() throws Exception {
        PartnerContractClauseVersion clause = PartnerContractClauseVersion.builder().id(1L).clauseIdentity("c1").build();
        when(partnerContractService.getApprovedClause(100L, 1, "c1", 99L)).thenReturn(clause);

        mockMvc.perform(get("/api/v1/partner-contracts/100/versions/1/clauses/c1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clauseIdentity").value("c1"));
    }

    @Test
    void testMalformedJsonReturns400() throws Exception {
        mockMvc.perform(patch("/api/v1/partner-contracts/100/extractions/ext-1/fields/titleKey")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ malformed_json"))
                .andExpect(status().isBadRequest());
    }
}
