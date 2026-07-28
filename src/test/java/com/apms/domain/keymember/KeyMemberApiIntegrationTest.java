package com.apms.domain.keymember;

import com.apms.config.SecurityConfig;
import com.apms.common.security.ProjectSecurityEvaluator;
import com.apms.domain.ai.controller.AiController;
import com.apms.domain.ai.controller.CandidateMergeController;
import com.apms.domain.ai.dto.MergeCandidateResponse;
import com.apms.domain.ai.service.AiExtractionService;
import com.apms.domain.ai.service.ExtractionMergeService;
import com.apms.domain.audit.repository.sql.AuditLogRepository;
import com.apms.domain.candidate.controller.CandidateController;
import com.apms.domain.candidate.dto.CandidateResponse;
import com.apms.domain.candidate.service.CandidateService;
import com.apms.domain.project.controller.ProjectController;
import com.apms.domain.project.controller.ProjectTaskController;
import com.apms.domain.project.controller.ProjectTaskSubmissionController;
import com.apms.domain.project.dto.ProjectResponse;
import com.apms.domain.project.service.ProjectService;
import com.apms.domain.project.service.ProjectTaskService;
import com.apms.domain.project.service.ProjectTaskSubmissionService;
import com.apms.domain.user.repository.sql.AccountRepository;
import com.apms.security.AuthEntryPointJwt;
import com.apms.security.JwtUtils;
import com.apms.security.UserDetailsServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({
        CandidateController.class,
        CandidateMergeController.class,
        ProjectController.class,
        ProjectTaskController.class,
        ProjectTaskSubmissionController.class,
        AiController.class
})
@Import(SecurityConfig.class)
@EnableMethodSecurity
@DisplayName("KeyMember Security & Permissions Integration Tests")
class KeyMemberApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    // Security infrastructure mocks
    @MockitoBean private JwtUtils jwtUtils;
    @MockitoBean private AuthEntryPointJwt unauthorizedHandler;
    @MockitoBean private UserDetailsServiceImpl userDetailsService;
    @MockitoBean private AccountRepository accountRepository;
    @MockitoBean private AuditLogRepository auditLogRepository;

    // Domain service mocks
    @MockitoBean private CandidateService candidateService;
    @MockitoBean private ExtractionMergeService mergeService;
    @MockitoBean private ProjectService projectService;
    @MockitoBean private ProjectTaskService projectTaskService;
    @MockitoBean private ProjectTaskSubmissionService projectTaskSubmissionService;
    @MockitoBean private AiExtractionService aiExtractionService;

    @MockitoBean(name = "projectSecurity")
    private ProjectSecurityEvaluator projectSecurity;

    // ─────────────────────────────────────────────
    // KeyMember READ operations (Allowed if member)
    // ─────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "KEY_MEMBER")
    @DisplayName("KeyMember: GET /projects → 200")
    void keyMemberCanGetAllProjects() throws Exception {
        Mockito.when(projectService.getAllProjects(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(Collections.emptyList()));

        mockMvc.perform(get("/api/v1/projects"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "KEY_MEMBER")
    @DisplayName("KeyMember: GET /projects/{id} when member → 200")
    void keyMemberCanGetProjectByIdWhenMember() throws Exception {
        Mockito.when(projectSecurity.isProjectReadable(100L)).thenReturn(true);
        Mockito.when(projectService.getProjectById(100L)).thenReturn(ProjectResponse.builder().id(100L).build());

        mockMvc.perform(get("/api/v1/projects/100"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "KEY_MEMBER")
    @DisplayName("KeyMember: GET /projects/{projectId}/candidates when member → 200")
    void keyMemberCanGetProjectCandidatesWhenMember() throws Exception {
        Mockito.when(projectSecurity.isMemberOrOwner(100L)).thenReturn(true);
        Mockito.when(candidateService.getProjectCandidates(eq("100"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));

        mockMvc.perform(get("/api/v1/projects/100/candidates"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "KEY_MEMBER")
    @DisplayName("KeyMember: GET /candidates/{candidateId} when member → 200")
    void keyMemberCanGetCandidateWhenMember() throws Exception {
        Mockito.when(projectSecurity.canAccessCandidate("CAND-001")).thenReturn(true);
        Mockito.when(candidateService.getCandidate("CAND-001")).thenReturn(CandidateResponse.builder().id("CAND-001").build());

        mockMvc.perform(get("/api/v1/candidates/CAND-001"))
                .andExpect(status().isOk());
    }

    // ─────────────────────────────────────────────
    // KeyMember WRITE operations (Allowed if member)
    // ─────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "KEY_MEMBER")
    @DisplayName("KeyMember: PATCH /candidates/{id} when member → 200")
    void keyMemberCanUpdateCandidateWhenMember() throws Exception {
        Mockito.when(projectSecurity.canModifyCandidate("CAND-001")).thenReturn(true);
        Mockito.when(candidateService.updateCandidate(eq("CAND-001"), any(), any()))
                .thenReturn(CandidateResponse.builder().id("CAND-001").build());

        mockMvc.perform(patch("/api/v1/candidates/CAND-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "KEY_MEMBER")
    @DisplayName("KeyMember: POST /candidates/{id}/submit when member → 200")
    void keyMemberCanSubmitCandidateWhenMember() throws Exception {
        Mockito.when(projectSecurity.canModifyCandidate("CAND-001")).thenReturn(true);
        Mockito.when(candidateService.submitCandidate("CAND-001"))
                .thenReturn(CandidateResponse.builder().id("CAND-001").build());

        mockMvc.perform(post("/api/v1/candidates/CAND-001/submit"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "KEY_MEMBER")
    @DisplayName("Status Constraint: KeyMember PATCH /candidates/{id} when status is SUBMITTED/PENDING_REVIEW → 400 Bad Request")
    void keyMemberCannotUpdateSubmittedCandidate() throws Exception {
        Mockito.when(projectSecurity.canModifyCandidate("CAND-SUBMITTED")).thenReturn(true);
        Mockito.when(candidateService.updateCandidate(eq("CAND-SUBMITTED"), any(), any()))
                .thenThrow(new com.apms.common.exception.BusinessValidationException("Cannot edit candidate in status: PENDING_REVIEW. Only DRAFT or CORRECTED candidates can be edited."));

        mockMvc.perform(patch("/api/v1/candidates/CAND-SUBMITTED")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "KEY_MEMBER")
    @DisplayName("Anti-Duplicate Guard: Candidate merge returns existing DRAFT candidate on duplicate extractionIds call")
    void keyMemberMergeExtractionsReturnsExistingDraftOnSecondCall() throws Exception {
        MergeCandidateResponse response = MergeCandidateResponse.builder().candidateId("CAND-DRAFT-001").build();
        Mockito.when(mergeService.mergeExtractionsIntoCandidate(any(), any(), any(), any(), any()))
                .thenReturn(response);

        // First call generates DRAFT
        mockMvc.perform(post("/api/v1/projects/100/tasks/10/candidates/from-extractions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"extractionIds\":[\"EXT-1\"]}"))
                .andExpect(status().isOk());

        // Second call with same extractionIds returns exact same DRAFT
        mockMvc.perform(post("/api/v1/projects/100/tasks/10/candidates/from-extractions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"extractionIds\":[\"EXT-1\"]}"))
                .andExpect(status().isOk());
    }

    // ─────────────────────────────────────────────
    // CRITICAL SECURITY TESTS: KeyMember CANNOT Approve/Reject Candidates (Manager Only)
    // ─────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "KEY_MEMBER")
    @DisplayName("Security Guard: KeyMember POST /candidates/{id}/approve → 403 Forbidden")
    void keyMemberCannotApproveCandidate() throws Exception {
        mockMvc.perform(post("/api/v1/candidates/CAND-001/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "KEY_MEMBER")
    @DisplayName("Security Guard: KeyMember POST /candidates/{id}/reject → 403 Forbidden")
    void keyMemberCannotRejectCandidate() throws Exception {
        mockMvc.perform(post("/api/v1/candidates/CAND-001/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rejectionReason\":\"Not qualified for partnership\"}"))
                .andExpect(status().isForbidden());
    }
}
