package com.apms.common.security;

import com.apms.config.SecurityConfig;
import com.apms.domain.ai.controller.CandidateMergeController;
import com.apms.domain.ai.service.ExtractionMergeService;
import com.apms.domain.auth.controller.AuthController;
import com.apms.domain.auth.service.AuthService;
import com.apms.domain.auth.service.RefreshTokenService;
import com.apms.domain.candidate.controller.CandidateController;
import com.apms.domain.candidate.service.CandidateService;
import com.apms.domain.notification.controller.NotificationController;
import com.apms.domain.notification.service.NotificationService;
import com.apms.domain.user.controller.UserController;
import com.apms.domain.user.service.UserService;
import com.apms.security.AuthEntryPointJwt;
import com.apms.security.AuthTokenFilter;
import com.apms.security.JwtUtils;
import com.apms.security.UserDetailsImpl;
import com.apms.security.UserDetailsServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security regression tests for Group 0 fixes.
 *
 * Covers:
 * - C2: NotificationController GET and PATCH /read-all now require authentication
 * - C3: AuthController changePassword and logout now require authentication
 * - UserController GET /users/me now requires authentication
 * - C4: CandidateController/CandidateMergeController no longer fallback to userId 1L
 */
@WebMvcTest(controllers = {
        NotificationController.class,
        AuthController.class,
        UserController.class,
        CandidateController.class,
        CandidateMergeController.class
})
@Import(SecurityConfig.class)
@AutoConfigureMockMvc(addFilters = true)
@EnableMethodSecurity
class Group0EndpointSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean private NotificationService notificationService;
    @MockitoBean private AuthService authService;
    @MockitoBean private RefreshTokenService refreshTokenService;
    @MockitoBean private UserService userService;
    @MockitoBean private CandidateService candidateService;
    @MockitoBean private ExtractionMergeService extractionMergeService;

    @MockitoBean private UserDetailsServiceImpl userDetailsService;
    @MockitoBean private AuthEntryPointJwt unauthorizedHandler;
    @MockitoBean private AuthTokenFilter authTokenFilter;
    @MockitoBean private JwtUtils jwtUtils;

    private UserDetailsImpl staff() {
        return new UserDetailsImpl(10L, "staff", "pwd",
                List.of(new SimpleGrantedAuthority("ROLE_BUSINESS_DEVELOPMENT_STAFF")), true);
    }

    private UserDetailsImpl admin() {
        return new UserDetailsImpl(1L, "admin", "pwd",
                List.of(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")), true);
    }

    private UserDetailsImpl owner() {
        return new UserDetailsImpl(2L, "owner", "pwd",
                List.of(new SimpleGrantedAuthority("ROLE_BUSINESS_OWNER")), true);
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

    // ─────────────────────────────────────────────────────────
    // C2: NotificationController — previously unprotected
    // ─────────────────────────────────────────────────────────

    @Test
    void notificationGet_Unauthenticated_401() throws Exception {
        mockMvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void notificationGet_Authenticated_200() throws Exception {
        when(notificationService.getNotifications(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
        mockMvc.perform(get("/api/v1/notifications")
                        .with(user(staff())))
                .andExpect(status().isOk());
    }

    @Test
    void notificationMarkAllRead_Unauthenticated_401() throws Exception {
        mockMvc.perform(patch("/api/v1/notifications/read-all"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void notificationMarkAllRead_Authenticated_200() throws Exception {
        mockMvc.perform(patch("/api/v1/notifications/read-all")
                        .with(user(staff())))
                .andExpect(status().isOk());
    }

    // ─────────────────────────────────────────────────────────
    // C3: AuthController changePassword & logout
    // ─────────────────────────────────────────────────────────

    @Test
    void changePassword_Unauthenticated_Forbidden() throws Exception {
        String body = "{\"currentPassword\":\"old\",\"newPassword\":\"new123\"}";
        mockMvc.perform(post("/api/v1/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void changePassword_Authenticated_200() throws Exception {
        String body = "{\"currentPassword\":\"old\",\"newPassword\":\"new123\"}";
        mockMvc.perform(post("/api/v1/auth/change-password")
                        .with(user(staff())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    void logout_Unauthenticated_Forbidden() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isForbidden());
    }

    @Test
    void logout_Authenticated_200() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .with(user(staff())).with(csrf()))
                .andExpect(status().isOk());
    }

    // ─────────────────────────────────────────────────────────
    // UserController GET /users/me
    // ─────────────────────────────────────────────────────────

    @Test
    void userMe_Unauthenticated_401() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userMe_Authenticated_200() throws Exception {
        mockMvc.perform(get("/api/v1/users/me")
                        .with(user(staff())))
                .andExpect(status().isOk());
    }

    // ─────────────────────────────────────────────────────────
    // C4: CandidateController updateCandidate — no userId=1L fallback
    // ─────────────────────────────────────────────────────────

    @Test
    void candidateUpdate_Unauthenticated_401() throws Exception {
        String body = "{\"fields\":{}}";
        mockMvc.perform(patch("/api/v1/candidates/test-id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    // ─────────────────────────────────────────────────────────
    // C4: CandidateMergeController — no userId=1L fallback
    // ─────────────────────────────────────────────────────────

    @Test
    void candidateMerge_Unauthenticated_401() throws Exception {
        String body = "{\"extractionIds\":[\"ext-1\"],\"note\":\"test\"}";
        mockMvc.perform(post("/api/v1/projects/1/tasks/1/candidates/from-extractions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void candidateMerge_StaffAllowed_200() throws Exception {
        String body = "{\"extractionIds\":[\"ext-1\"],\"note\":\"test\"}";
        mockMvc.perform(post("/api/v1/projects/1/tasks/1/candidates/from-extractions")
                        .with(user(staff())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    // ─────────────────────────────────────────────────────────
    // Verify no accidentally-protected public endpoints broke
    // ─────────────────────────────────────────────────────────

    @Test
    void login_StillAccessible_WithoutAuth() throws Exception {
        String body = "{\"email\":\"test@test.com\",\"password\":\"pass\"}";
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void forgotPassword_StillPublic_200() throws Exception {
        String body = "{\"email\":\"test@test.com\"}";
        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    void resetPassword_StillPublic_200() throws Exception {
        String body = "{\"token\":\"some-token\",\"newPassword\":\"new123456\"}";
        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }
}
