package com.apms.domain.ai.controller;

import com.apms.common.response.ApiResponse;
import com.apms.domain.ai.dto.AddAiApiKeyRequest;
import com.apms.domain.ai.dto.AiApiKeyDto;
import com.apms.domain.ai.dto.ReplaceAiApiKeysRequest;
import com.apms.domain.ai.dto.TestAiApiKeyResponse;
import com.apms.domain.ai.dto.UpdateAiApiKeyStatusRequest;
import com.apms.domain.ai.service.provider.GeminiApiKeyProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/admin/ai-keys")
@RequiredArgsConstructor
public class AdminAiKeyController {

    private final GeminiApiKeyProvider apiKeyProvider;

    @GetMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<List<AiApiKeyDto>>> getAllKeys() {
        return ResponseEntity.ok(ApiResponse.success(apiKeyProvider.getAllKeys()));
    }

    @PostMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<AiApiKeyDto>> addKey(@Valid @RequestBody AddAiApiKeyRequest request) {
        AiApiKeyDto dto = apiKeyProvider.addKey(request.getApiKey(), request.getLabel());
        return ResponseEntity.ok(ApiResponse.success(dto));
    }

    @PutMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<List<AiApiKeyDto>>> replaceKeys(@RequestBody ReplaceAiApiKeysRequest request) {
        apiKeyProvider.replaceKeys(request.getApiKeys());
        return ResponseEntity.ok(ApiResponse.success(apiKeyProvider.getAllKeys()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> removeKey(@PathVariable String id) {
        boolean removed = apiKeyProvider.removeKey(id);
        if (!removed) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<AiApiKeyDto>> setKeyStatus(
            @PathVariable String id,
            @RequestBody UpdateAiApiKeyStatusRequest request) {
        boolean enabled = request.getEnabled() != null && request.getEnabled();
        AiApiKeyDto dto = apiKeyProvider.setKeyEnabled(id, enabled);
        return ResponseEntity.ok(ApiResponse.success(dto));
    }

    @PostMapping("/{id}/test")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<TestAiApiKeyResponse>> testKey(@PathVariable String id) {
        TestAiApiKeyResponse response = apiKeyProvider.testKey(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/{id}/reveal")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<com.apms.domain.ai.dto.RevealAiApiKeyResponse>> revealKey(@PathVariable String id) {
        com.apms.domain.ai.dto.RevealAiApiKeyResponse response = apiKeyProvider.revealKey(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/reload")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<com.apms.domain.ai.dto.ReloadAiKeysResponse>> reloadKeys() {
        apiKeyProvider.reload();
        int activeCount = apiKeyProvider.getActiveKeys().size();
        List<AiApiKeyDto> allKeys = apiKeyProvider.getAllKeys();
        return ResponseEntity.ok(ApiResponse.success(new com.apms.domain.ai.dto.ReloadAiKeysResponse(activeCount, allKeys)));
    }
}
