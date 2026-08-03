package com.apms.domain.security.controller;

import com.apms.common.response.ApiResponse;
import com.apms.domain.security.dto.StepUpChallengeRequest;
import com.apms.domain.security.dto.StepUpChallengeResponse;
import com.apms.domain.security.dto.StepUpVerifyRequest;
import com.apms.domain.security.dto.StepUpVerifyResponse;
import com.apms.domain.security.service.StepUpAuthenticationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/security/step-up")
@RequiredArgsConstructor
public class StepUpAuthenticationController {

    private final StepUpAuthenticationService stepUpAuthService;

    @PostMapping("/challenges")
    public ResponseEntity<ApiResponse<StepUpChallengeResponse>> createChallenge(
            @Valid @RequestBody StepUpChallengeRequest request,
            HttpServletRequest httpRequest) {
        
        String clientIp = getClientIp(httpRequest);
        
        try {
            StepUpChallengeResponse response = stepUpAuthService.createChallenge(request.getPurpose(), clientIp);
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (RuntimeException ex) {
            if ("MFA_DELIVERY_UNAVAILABLE".equals(ex.getMessage()) || "STEP_UP_NOT_CONFIGURED".equals(ex.getMessage())) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(ApiResponse.error(ex.getMessage()));
            }
            throw ex;
        }
    }

    @PostMapping("/challenges/{challengeId}/verify")
    public ResponseEntity<ApiResponse<StepUpVerifyResponse>> verifyChallenge(
            @PathVariable Long challengeId,
            @Valid @RequestBody StepUpVerifyRequest request) {
        
        StepUpVerifyResponse response = stepUpAuthService.verifyChallenge(challengeId, request.getOtp());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    private String getClientIp(HttpServletRequest request) {
        // For security, do not blindly trust X-Forwarded-For unless proxy is configured
        return request.getRemoteAddr();
    }
}
