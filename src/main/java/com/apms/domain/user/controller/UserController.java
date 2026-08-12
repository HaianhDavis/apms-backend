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

    @GetMapping("/users")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<List<UserProfileResponse>>> getAllUsers() {
        return ResponseEntity.ok(ApiResponse.success(userService.getAllUsers()));
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

    @PatchMapping("/users/{userId}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<UserProfileResponse>> updateUser(
            @PathVariable Long userId,
            @Valid @RequestBody UpdateUserRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        UserProfileResponse response = userService.updateUser(userId, request, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(response, "User updated successfully"));
    }

    /**
     * Admin password reset. Accepts only newPassword (BCrypt hashed server-side).
     * Password is NEVER returned in response and NEVER logged in audit detail.
     * Only SYSTEM_ADMIN can call this endpoint.
     */
    @PatchMapping("/users/{userId}/password")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> resetUserPassword(
            @PathVariable Long userId,
            @Valid @RequestBody ResetPasswordRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        userService.resetUserPassword(userId, request.getNewPassword(), currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(null, "Password reset successfully"));
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

    /**
     * Soft delete a user. Sets deletedAt timestamp and deactivates the account.
     * Cannot delete own account or the last SYSTEM_ADMIN.
     */
    @DeleteMapping("/users/{userId}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteUser(
            @PathVariable Long userId,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        userService.softDeleteUser(userId, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(null, "User deleted successfully"));
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
