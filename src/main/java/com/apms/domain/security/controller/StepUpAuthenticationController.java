package com.apms.domain.security.controller;

import com.apms.common.response.ApiResponse;
import com.apms.domain.security.dto.StepUpVerifyResponse;
import com.apms.domain.security.dto.TotpDto.StepUpStatusResponse;
import com.apms.domain.security.dto.TotpDto.TotpStepUpVerifyRequest;
import com.apms.domain.security.service.StepUpAuthenticationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/security/step-up")
@RequiredArgsConstructor
public class StepUpAuthenticationController {

    private final StepUpAuthenticationService stepUpAuthService;

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<StepUpStatusResponse>> getStatus(
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String resourceId,
            @RequestHeader(value = "X-Step-Up-Token", required = false) String ownerSecureToken) {
        
        StepUpStatusResponse response = stepUpAuthService.getStepUpStatus(scope, resourceId, ownerSecureToken);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/totp/verify")
    public ResponseEntity<ApiResponse<StepUpVerifyResponse>> verifyTotpStepUp(
            @Valid @RequestBody TotpStepUpVerifyRequest request) {
        
        StepUpVerifyResponse response = stepUpAuthService.verifyTotpStepUp(
                request.getCode(), 
                request.getScope(), 
                request.getResourceId()
        );
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
