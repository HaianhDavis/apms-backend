package com.apms.domain.admin.controller;

import com.apms.common.response.ApiResponse;
import com.apms.domain.admin.dto.IpWhitelistRequest;
import com.apms.domain.admin.dto.IpWhitelistResponse;
import com.apms.domain.admin.service.AdminIpWhitelistService;
import com.apms.security.UserDetailsImpl;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/security/ip-whitelist")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class AdminIpWhitelistController {

    private final AdminIpWhitelistService ipWhitelistService;

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> getWhitelist() {
        List<IpWhitelistResponse> entries = ipWhitelistService.getAllEntries();
        boolean enabled = ipWhitelistService.isWhitelistEnabled();

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "entries", entries,
                "enabled", enabled
        )));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<IpWhitelistResponse>> addEntry(
            @Valid @RequestBody IpWhitelistRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        IpWhitelistResponse response = ipWhitelistService.addEntry(request, currentUser.getId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "IP added to whitelist"));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<IpWhitelistResponse>> updateEntry(
            @PathVariable Long id,
            @Valid @RequestBody IpWhitelistRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        IpWhitelistResponse response = ipWhitelistService.updateEntry(id, request, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(response, "IP whitelist entry updated"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteEntry(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        ipWhitelistService.deleteEntry(id, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(null, "IP removed from whitelist"));
    }

    @PatchMapping("/status")
    public ResponseEntity<ApiResponse<Void>> setEnabled(
            @RequestBody Map<String, Boolean> body,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        Boolean enabled = body.get("enabled");
        if (enabled == null) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("'enabled' field is required"));
        }

        ipWhitelistService.setWhitelistEnabled(enabled, currentUser.getId());
        String msg = enabled ? "IP whitelist enforcement ENABLED" : "IP whitelist enforcement DISABLED";
        return ResponseEntity.ok(ApiResponse.success(null, msg));
    }
}
