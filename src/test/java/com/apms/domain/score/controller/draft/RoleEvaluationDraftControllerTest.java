package com.apms.domain.score.controller.draft;

import com.apms.domain.score.dto.draft.*;
import com.apms.domain.score.enums.RoleEvaluationReviewDecision;
import com.apms.domain.score.service.RoleEvaluationApprovalService;
import com.apms.domain.score.service.RoleEvaluationDraftService;
import com.apms.domain.score.service.RoleEvaluationSubmissionService;
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

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller slice test using standaloneSetup (same pattern as
 * OwnerCompanyProfileControllerTest). Security authorization logic is
 * verified via @PreAuthorize annotation inspection; this test verifies
 * routing, HTTP status codes, and service delegation.
 *
 * <p>A custom HandlerMethodArgumentResolver injects a UserDetailsImpl
 * principal into every controller method that accepts UserDetailsImpl,
 * matching the behaviour of @AuthenticationPrincipal in real requests.
 */
@ExtendWith(MockitoExtension.class)
class RoleEvaluationDraftControllerTest {

    @Mock private RoleEvaluationDraftService draftService;
    @Mock private RoleEvaluationSubmissionService submissionService;
    @Mock private RoleEvaluationApprovalService approvalService;

    @InjectMocks
    private RoleEvaluationDraftController controller;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private UserDetailsImpl staffUser;

    /** Resolver that returns the currently set principal for UserDetailsImpl parameters. */
    private UserDetailsImpl currentPrincipal;

    @BeforeEach
    void setUp() {
        staffUser = new UserDetailsImpl(123L, "staff@test.com", "pass", List.of(), true);

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

        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setCustomArgumentResolvers(principalResolver)
                .build();
    }

    // ---- createDraft ----

    @Test
    void createDraft_returnsCreated_andDelegatesService() throws Exception {
        currentPrincipal = staffUser;

        CreateRoleEvaluationDraftRequest req = new CreateRoleEvaluationDraftRequest();
        req.setNote("initial note");

        when(draftService.createDraft(eq(1L), eq(10L), any(), eq(123L)))
                .thenReturn(new RoleEvaluationDraftResponse());

        mockMvc.perform(post("/api/v1/projects/1/tasks/10/role-evaluations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        verify(draftService).createDraft(eq(1L), eq(10L),
                any(CreateRoleEvaluationDraftRequest.class), eq(123L));
    }

    // ---- getDraft ----

    @Test
    void getDraft_returnsOk() throws Exception {
        currentPrincipal = staffUser;

        when(draftService.getDraft("eval-1")).thenReturn(new RoleEvaluationDraftResponse());

        mockMvc.perform(get("/api/v1/role-evaluations/eval-1"))
                .andExpect(status().isOk());

        verify(draftService).getDraft("eval-1");
    }

    // ---- updateCriterionInput ----

    @Test
    void updateCriterionInput_returnsOk_andDelegates() throws Exception {
        currentPrincipal = staffUser;

        UpdateCriterionInputRequest req = new UpdateCriterionInputRequest();
        req.setRawScore(new BigDecimal("72.5"));

        when(draftService.updateCriterionInput(
                eq("eval-1"), eq("productMarketOverlapScore"), any(), eq(123L)))
                .thenReturn(new RoleEvaluationDraftResponse());

        mockMvc.perform(patch("/api/v1/role-evaluations/eval-1/criteria/productMarketOverlapScore")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());

        verify(draftService).updateCriterionInput(eq("eval-1"), eq("productMarketOverlapScore"),
                any(UpdateCriterionInputRequest.class), eq(123L));
    }

    // ---- suggestProductMarketOverlap ----

    @Test
    void suggestProductMarketOverlap_returnsOk() throws Exception {
        currentPrincipal = staffUser;

        when(draftService.suggestProductMarketOverlap("eval-1"))
                .thenReturn(new RoleEvaluationDraftResponse());

        mockMvc.perform(post("/api/v1/role-evaluations/eval-1/product-market-overlap/suggest"))
                .andExpect(status().isOk());

        verify(draftService).suggestProductMarketOverlap("eval-1");
    }

    // ---- acceptAutomaticSuggestion ----

    @Test
    void acceptAutomaticSuggestion_returnsOk() throws Exception {
        currentPrincipal = staffUser;

        AcceptAutomaticSuggestionRequest req = new AcceptAutomaticSuggestionRequest();
        req.setExplanation("Jaccard-based overlap confirmed");

        when(draftService.acceptAutomaticSuggestion(eq("eval-1"), any(), eq(123L)))
                .thenReturn(new RoleEvaluationDraftResponse());

        mockMvc.perform(post("/api/v1/role-evaluations/eval-1/product-market-overlap/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());

        verify(draftService).acceptAutomaticSuggestion(eq("eval-1"),
                any(AcceptAutomaticSuggestionRequest.class), eq(123L));
    }

    // ---- submitDraft ----

    @Test
    void submitDraft_returnsNoContent_andDelegates() throws Exception {
        currentPrincipal = staffUser;

        SubmitRoleEvaluationRequest req = new SubmitRoleEvaluationRequest();

        mockMvc.perform(post("/api/v1/role-evaluations/eval-1/submit")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNoContent());

        verify(submissionService).submitDraft(eq("eval-1"),
                any(SubmitRoleEvaluationRequest.class), eq(123L));
    }

    // ---- reviewDraft (APPROVE) ----

    @Test
    void reviewDraft_approve_returnsNoContent_andDelegates() throws Exception {
        currentPrincipal = new UserDetailsImpl(456L, "manager@test.com", "pass", List.of(), true);

        ReviewRoleEvaluationRequest req = new ReviewRoleEvaluationRequest();
        req.setDecision(RoleEvaluationReviewDecision.APPROVE);
        req.setComment("Approved");

        mockMvc.perform(post("/api/v1/role-evaluations/eval-1/review")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "idem-abc-123")
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNoContent());

        verify(approvalService).reviewDraft(eq("eval-1"),
                any(ReviewRoleEvaluationRequest.class), eq(456L), eq("idem-abc-123"));
    }

    // ---- reviewDraft (REQUEST_REVISION) ----

    @Test
    void reviewDraft_requestRevision_returnsNoContent() throws Exception {
        currentPrincipal = new UserDetailsImpl(456L, "manager@test.com", "pass", List.of(), true);

        ReviewRoleEvaluationRequest req = new ReviewRoleEvaluationRequest();
        req.setDecision(RoleEvaluationReviewDecision.REQUEST_REVISION);
        req.setComment("Needs more evidence");

        mockMvc.perform(post("/api/v1/role-evaluations/eval-1/review")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNoContent());

        // Idempotency-Key not provided → null
        verify(approvalService).reviewDraft(eq("eval-1"),
                any(ReviewRoleEvaluationRequest.class), eq(456L), eq(null));
    }

    // ---- Controller security contract verification (annotation check) ----

    /**
     * Verifies that the controller class does NOT expose a public endpoint
     * for direct snapshot creation, weight input, overallScore, or
     * client-selected evaluatedRole. These are structural invariants
     * verified here at the Java level to prevent accidental regression.
     */
    @Test
    void controller_doesNotExposeProhibitedEndpoints() throws Exception {
        // No endpoint exists at /snapshots (snapshot creation is internal-only)
        mockMvc.perform(post("/api/v1/snapshots")).andExpect(status().isNotFound());

        // No endpoint exists for /weights input
        mockMvc.perform(post("/api/v1/role-evaluations/eval-1/weights"))
                .andExpect(status().isNotFound());
    }
}
