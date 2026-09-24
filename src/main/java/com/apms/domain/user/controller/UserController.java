package com.apms.domain.user.controller;

import com.apms.common.enums.SystemRole;
import com.apms.common.response.ApiResponse;
import com.apms.domain.user.dto.*;
import com.apms.domain.user.service.UserService;
import com.apms.security.UserDetailsImpl;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/users/me")
    public ResponseEntity<ApiResponse<UserProfileResponse>> getCurrentUser(
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        UserProfileResponse response = userService.getCurrentUserProfile(currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/users/search")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<ApiResponse<List<UserProfileResponse>>> searchUsers(
            @RequestParam(required = false) String email) {

        return ResponseEntity.ok(ApiResponse.success(userService.searchActiveUsersByEmail(email)));
    }

    @PostMapping("/users")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<UserProfileResponse>> createUser(
            @Valid @RequestBody CreateUserRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        UserProfileResponse response = userService.createUser(request, currentUser.getId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "User created successfully"));
    }

    @GetMapping("/users")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<List<UserProfileResponse>>> listUsers() {
        return ResponseEntity.ok(ApiResponse.success(userService.listUsers()));
    }

    @PatchMapping("/users/{userId}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<UserProfileResponse>> updateUser(
            @PathVariable Long userId,
            @Valid @RequestBody UpdateUserRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        UserProfileResponse response = userService.updateUser(userId, request, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(response, "User updated successfully"));
    }

    @PatchMapping("/users/{userId}/status")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> updateUserStatus(
            @PathVariable Long userId,
            @Valid @RequestBody UpdateUserStatusRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        userService.updateUserStatus(userId, request, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(null, "User status updated"));
    }

    @PatchMapping("/users/{userId}/password")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> resetPassword(
            @PathVariable Long userId,
            @Valid @RequestBody ResetPasswordRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        userService.resetPassword(userId, request, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(null, "Password reset"));
    }

    @GetMapping("/roles")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<List<SystemRole>>> getRoles() {
        return ResponseEntity.ok(ApiResponse.success(userService.getAllRoles()));
    }

    @PostMapping("/users/{userId}/roles")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> assignUserRoles(
            @PathVariable Long userId,
            @Valid @RequestBody AssignUserRolesRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        userService.assignUserRoles(userId, request, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(null, "User roles updated"));
    }
}