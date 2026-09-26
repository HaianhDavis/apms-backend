package com.apms.domain.security.controller;

import com.apms.common.response.ApiResponse;
import com.apms.domain.security.dto.StepUpVerifyResponse;
import com.apms.domain.security.dto.TotpDto.TotpDisableRequest;
import com.apms.domain.security.dto.TotpDto.TotpEnrollmentConfirmRequest;
import com.apms.domain.security.dto.TotpDto.TotpEnrollmentStartResponse;
import com.apms.domain.security.dto.TotpDto.TotpStatusResponse;
import com.apms.domain.security.service.TotpEnrollmentService;
import com.apms.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/security/totp")
@RequiredArgsConstructor
public class TotpEnrollmentController {

    private final TotpEnrollmentService enrollmentService;

    @GetMapping("/status")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<TotpStatusResponse>> getStatus() {
        UserDetailsImpl currentUser = getCurrentUser();
        TotpStatusResponse response = enrollmentService.getStatus(currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/enrollment")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<TotpEnrollmentStartResponse>> startEnrollment() {
        UserDetailsImpl currentUser = getCurrentUser();
        TotpEnrollmentStartResponse response = enrollmentService.startEnrollment(currentUser.getId(), currentUser.getUsername());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .body(ApiResponse.success(response));
    }

    @PostMapping("/enrollment/confirm")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<StepUpVerifyResponse>> confirmEnrollment(@RequestBody TotpEnrollmentConfirmRequest request) {
        UserDetailsImpl currentUser = getCurrentUser();
        StepUpVerifyResponse response = enrollmentService.confirmEnrollment(currentUser.getId(), request.getEnrollmentId(), request.getCode());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .body(ApiResponse.success(response, "Mã Authenticator đã được xác minh thành công."));
    }

    @PostMapping("/disable")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Void>> disableTotp(@RequestBody(required = false) TotpDisableRequest request) {
        UserDetailsImpl currentUser = getCurrentUser();
        String code = request != null ? request.getCode() : null;
        enrollmentService.disableTotp(currentUser.getId(), code);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .body(ApiResponse.success(null, "Xác thực 2 yếu tố đã được vô hiệu hóa thành công."));
    }

    private UserDetailsImpl getCurrentUser() {
        if (SecurityContextHolder.getContext().getAuthentication() == null ||
                !(SecurityContextHolder.getContext().getAuthentication().getPrincipal() instanceof UserDetailsImpl)) {
            throw new AccessDeniedException("Unauthorized");
        }
        return (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
