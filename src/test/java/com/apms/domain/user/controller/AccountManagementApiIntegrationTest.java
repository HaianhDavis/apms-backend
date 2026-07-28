package com.apms.domain.user.controller;

import com.apms.common.enums.SystemRole;
import com.apms.common.exception.BusinessValidationException;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.common.exception.GlobalExceptionHandler;
import com.apms.domain.admin.controller.AdminController;
import com.apms.domain.admin.dto.AccountAdminResponse;
import com.apms.domain.admin.dto.CreateAccountRequest;
import com.apms.domain.admin.dto.UpdateAccountRequest;
import com.apms.domain.admin.service.AdminUserService;
import com.apms.domain.user.dto.AssignUserRolesRequest;
import com.apms.domain.user.dto.UpdateUserStatusRequest;
import com.apms.domain.user.service.UserService;
import com.apms.security.UserDetailsImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class AccountManagementApiIntegrationTest {

    private MockMvc mockMvcAdmin;
    private MockMvc mockMvcUser;
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private AdminUserService adminUserService;

    @Mock
    private UserService userService;

    @InjectMocks
    private AdminController adminController;

    @InjectMocks
    private UserController userController;

    @BeforeEach
    void setUp() {
        mockMvcAdmin = MockMvcBuilders.standaloneSetup(adminController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvcUser = MockMvcBuilders.standaloneSetup(userController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new org.springframework.web.method.support.HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(org.springframework.core.MethodParameter parameter) {
                        return parameter.getParameterType().equals(UserDetailsImpl.class);
                    }

                    @Override
                    public Object resolveArgument(org.springframework.core.MethodParameter parameter, org.springframework.web.method.support.ModelAndViewContainer mavContainer, org.springframework.web.context.request.NativeWebRequest webRequest, org.springframework.web.bind.support.WebDataBinderFactory binderFactory) {
                        return new UserDetailsImpl(2L, "owner@apms.com", "pass", java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_BUSINESS_OWNER")), true);
                    }
                })
                .build();
    }

    // ─────────────────────────────────────────────
    // 1. GET /api/v1/accounts
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/accounts returns 200 OK with paginated structure")
    void getAccounts_shouldReturn200WithPageResponse() throws Exception {
        AccountAdminResponse acc1 = AccountAdminResponse.builder().id(1L).email("admin@apms.com").build();
        when(adminUserService.getAccounts(any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(acc1)));

        mockMvcAdmin.perform(get("/api/v1/accounts?page=0&size=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].email").value("admin@apms.com"));
    }

    // ─────────────────────────────────────────────
    // 2. POST /api/v1/accounts
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/v1/accounts with valid data returns 201 Created")
    void createAccount_withValidData_shouldReturn201() throws Exception {
        CreateAccountRequest req = new CreateAccountRequest();
        req.setEmail("newuser@apms.com");
        req.setUsername("newuser");
        req.setPassword("123456");
        req.setName("New User");
        req.setRole("ROLE_BUSINESS_DIRECTOR");

        AccountAdminResponse res = AccountAdminResponse.builder().id(10L).email("newuser@apms.com").build();
        when(adminUserService.createAccount(any())).thenReturn(res);

        mockMvcAdmin.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(10));
    }

    @Test
    @DisplayName("POST /api/v1/accounts with duplicate email returns 400 Bad Request")
    void createAccount_duplicateEmail_shouldReturn400() throws Exception {
        CreateAccountRequest req = new CreateAccountRequest();
        req.setEmail("existing@apms.com");
        req.setUsername("existing");
        req.setPassword("123456");
        req.setName("Existing User");
        req.setRole("ROLE_STAFF");

        when(adminUserService.createAccount(any()))
                .thenThrow(new BusinessValidationException("Email already exists: existing@apms.com"));

        mockMvcAdmin.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Email already exists: existing@apms.com"));
    }

    // ─────────────────────────────────────────────
    // 3. PUT /api/v1/accounts/{id}
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("PUT /api/v1/accounts/{id} with valid data returns 200 OK")
    void updateAccount_valid_shouldReturn200() throws Exception {
        UpdateAccountRequest req = new UpdateAccountRequest();
        req.setName("Updated Name");

        AccountAdminResponse res = AccountAdminResponse.builder().id(5L).name("Updated Name").build();
        when(adminUserService.updateAccount(eq(5L), any())).thenReturn(res);

        mockMvcAdmin.perform(put("/api/v1/accounts/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("Updated Name"));
    }

    @Test
    @DisplayName("PUT /api/v1/accounts/{id} non-existent ID returns 404 Not Found")
    void updateAccount_notFound_shouldReturn404() throws Exception {
        when(adminUserService.updateAccount(eq(999L), any()))
                .thenThrow(new ResourceNotFoundException("Account not found with id: 999"));

        mockMvcAdmin.perform(put("/api/v1/accounts/999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ─────────────────────────────────────────────
    // 4. POST /api/v1/users/{userId}/roles
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/v1/users/{userId}/roles when Owner assigns Admin returns 403 Forbidden")
    void assignRoles_ownerAssignsAdmin_shouldReturn403() throws Exception {
        doThrow(new AccessDeniedException("Bạn không có quyền gán role cao hơn hoặc ngang cấp mình"))
                .when(userService).assignUserRoles(eq(3L), any(), any());

        String jsonPayload = "{\"roles\": [\"SYSTEM_ADMIN\"]}";

        mockMvcUser.perform(post("/api/v1/users/3/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Bạn không có quyền gán role cao hơn hoặc ngang cấp mình"));
    }

    // ─────────────────────────────────────────────
    // 5. PATCH /api/v1/accounts/{id}/status & /users/{id}/status
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("PATCH /api/v1/accounts/{id}/status when self-lock returns 400 Bad Request")
    void toggleStatus_selfLock_shouldReturn400() throws Exception {
        doThrow(new BusinessValidationException("Không thể tự khóa tài khoản của chính mình"))
                .when(userService).updateUserStatus(eq(1L), any(), any());

        String jsonPayload = "{\"enabled\": false}";

        mockMvcUser.perform(patch("/api/v1/users/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Không thể tự khóa tài khoản của chính mình"));
    }

    @Test
    @DisplayName("PATCH /api/v1/users/{id}/status when last active Admin returns 400 Bad Request")
    void toggleStatus_lastActiveAdmin_shouldReturn400() throws Exception {
        doThrow(new BusinessValidationException("Không thể khóa tài khoản Admin cuối cùng còn hoạt động trong hệ thống"))
                .when(userService).updateUserStatus(eq(1L), any(), any());

        String jsonPayload = "{\"enabled\": false}";

        mockMvcUser.perform(patch("/api/v1/users/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Không thể khóa tài khoản Admin cuối cùng còn hoạt động trong hệ thống"));
    }

    @Test
    @DisplayName("PATCH /api/v1/accounts/{id}/status when valid toggle returns 200 OK")
    void toggleStatus_valid_shouldReturn200() throws Exception {
        AccountAdminResponse res = AccountAdminResponse.builder().id(2L).active(false).build();
        when(adminUserService.toggleAccountStatus(2L)).thenReturn(res);

        mockMvcAdmin.perform(patch("/api/v1/accounts/2/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
