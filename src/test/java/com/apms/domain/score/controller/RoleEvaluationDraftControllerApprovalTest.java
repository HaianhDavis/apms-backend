package com.apms.domain.score.controller;

import com.apms.domain.score.controller.draft.RoleEvaluationDraftController;
import com.apms.domain.score.dto.draft.ReviewRoleEvaluationRequest;
import com.apms.domain.score.enums.RoleEvaluationReviewDecision;
import com.apms.domain.score.service.CompetitorSuggestionGenerationService;
import com.apms.domain.score.service.PartnerDataSufficiencyEvaluator;
import com.apms.domain.score.service.PartnerSuggestionGenerationService;
import com.apms.domain.score.service.PartnerSuggestionReviewService;
import com.apms.domain.score.service.RoleEvaluationApprovalService;
import com.apms.domain.score.service.RoleEvaluationDraftService;
import com.apms.domain.score.service.RoleEvaluationSecurityService;
import com.apms.domain.score.service.RoleEvaluationSubmissionService;
import org.springframework.context.annotation.Import;
import com.apms.config.SecurityConfig;
import com.apms.security.UserDetailsImpl;
import com.apms.security.JwtUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RoleEvaluationDraftController.class)
@Import(SecurityConfig.class)
public class RoleEvaluationDraftControllerApprovalTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private RoleEvaluationDraftService draftService;

    @MockBean
    private RoleEvaluationSubmissionService submissionService;

    @MockBean
    private RoleEvaluationApprovalService approvalService;

    @MockBean
    private CompetitorSuggestionGenerationService suggestionGenerationService;

    @MockBean
    private PartnerSuggestionGenerationService partnerSuggestionGenerationService;

    @MockBean
    private PartnerDataSufficiencyEvaluator dataSufficiencyEvaluator;

    @MockBean
    private com.apms.domain.score.service.PotentialPartnerDataSufficiencyEvaluator potentialPartnerDataSufficiencyEvaluator;

    @MockBean
    private PartnerSuggestionReviewService partnerSuggestionReviewService;

    @MockBean
    private RoleEvaluationSecurityService securityService;

    @MockBean
    private JwtUtils jwtUtils;

    @MockBean
    private com.apms.security.UserDetailsServiceImpl userDetailsService;

    @MockBean
    private com.apms.security.AuthEntryPointJwt authEntryPointJwt;

    private UserDetailsImpl userDetails;

    @BeforeEach
    void setUp() throws java.io.IOException, jakarta.servlet.ServletException {
        userDetails = new UserDetailsImpl(
                100L,
                "test@example.com",
                "password",
                List.of(new SimpleGrantedAuthority("ROLE_MANAGER")),
                true
        );

        org.mockito.Mockito.doAnswer(invocation -> {
            jakarta.servlet.http.HttpServletResponse response = invocation.getArgument(1);
            response.sendError(401, "Unauthorized");
            return null;
        }).when(authEntryPointJwt).commence(any(), any(), any());
    }

    @Test
    void testReviewDraftAsManager() throws Exception {
        ReviewRoleEvaluationRequest request = new ReviewRoleEvaluationRequest();
        request.setDecision(RoleEvaluationReviewDecision.APPROVE);
        request.setComment("Looks good");

        com.apms.domain.score.draft.RoleEvaluationDraft mockDraft = new com.apms.domain.score.draft.RoleEvaluationDraft();
        mockDraft.setStatus(com.apms.domain.score.enums.RoleEvaluationStatus.APPROVAL_PROCESSING);
        mockDraft.setCurrentApprovedVersionId("ver-1");
        mockDraft.setCurrentApprovedVersionNumber(2);

        org.mockito.Mockito.when(draftService.getRawDraft("draft-1")).thenReturn(mockDraft);

        mockMvc.perform(post("/api/v1/role-evaluations/draft-1/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Idempotency-Key", "key-123")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .with(SecurityMockMvcRequestPostProcessors.user(userDetails)))
                .andExpect(status().isAccepted())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("APPROVAL_PROCESSING"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.evaluationId").value("draft-1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.approvedVersionId").value("ver-1"));

        verify(approvalService).reviewDraft(eq("draft-1"), any(ReviewRoleEvaluationRequest.class), eq(100L), eq("key-123"));
    }

    @Test
    void testReviewDraftUnauthorized() throws Exception {
        ReviewRoleEvaluationRequest request = new ReviewRoleEvaluationRequest();
        request.setDecision(RoleEvaluationReviewDecision.APPROVE);

        mockMvc.perform(post("/api/v1/role-evaluations/draft-1/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isUnauthorized());

        org.mockito.Mockito.verifyNoInteractions(approvalService);
    }

    @Test
    void testReviewDraftForbiddenForStaff() throws Exception {
        UserDetailsImpl staffUser = new UserDetailsImpl(
                101L,
                "staff@example.com",
                "password",
                List.of(new SimpleGrantedAuthority("ROLE_STAFF")),
                true
        );

        ReviewRoleEvaluationRequest request = new ReviewRoleEvaluationRequest();
        request.setDecision(RoleEvaluationReviewDecision.APPROVE);

        mockMvc.perform(post("/api/v1/role-evaluations/draft-1/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .with(SecurityMockMvcRequestPostProcessors.user(staffUser)))
                .andExpect(status().isForbidden());

        org.mockito.Mockito.verifyNoInteractions(approvalService);
    }

    @Test
    void testReviewDraftUnrelatedManager() throws Exception {
        ReviewRoleEvaluationRequest request = new ReviewRoleEvaluationRequest();
        request.setDecision(RoleEvaluationReviewDecision.APPROVE);

        org.mockito.Mockito.doThrow(new org.springframework.security.access.AccessDeniedException("User is not authorized as Manager for this project"))
                .when(approvalService).reviewDraft(eq("draft-1"), any(ReviewRoleEvaluationRequest.class), eq(100L), any());

        mockMvc.perform(post("/api/v1/role-evaluations/draft-1/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .with(SecurityMockMvcRequestPostProcessors.user(userDetails)))
                .andExpect(status().isForbidden());
    }

    @Test
    void testReviewDraftScopeMismatch() throws Exception {
        ReviewRoleEvaluationRequest request = new ReviewRoleEvaluationRequest();
        request.setDecision(RoleEvaluationReviewDecision.APPROVE);

        org.mockito.Mockito.doThrow(new org.springframework.security.access.AccessDeniedException("evaluation/project alignment failed"))
                .when(approvalService).reviewDraft(eq("draft-1"), any(ReviewRoleEvaluationRequest.class), eq(100L), any());

        mockMvc.perform(post("/api/v1/role-evaluations/draft-1/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .with(SecurityMockMvcRequestPostProcessors.user(userDetails)))
                .andExpect(status().isForbidden());
    }
}
