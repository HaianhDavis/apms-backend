package com.apms.domain.assistant.controller;

import com.apms.common.response.ApiResponse;
import com.apms.domain.assistant.dto.AiChatResponse;
import com.apms.domain.assistant.dto.OwnerAiChatRequest;
import com.apms.domain.assistant.service.OwnerAiAssistantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.apms.domain.assistant.dto.CompanyAutocompleteItemDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@RestController
@RequestMapping("/api/v1/owner/ai-assistant")
@RequiredArgsConstructor
public class OwnerAiAssistantController {

    private final OwnerAiAssistantService ownerAiAssistantService;

    @PostMapping("/chat")
    @PreAuthorize("hasRole('BUSINESS_OWNER')")
    public ResponseEntity<ApiResponse<AiChatResponse>> ownerChat(
            @Valid @RequestBody OwnerAiChatRequest request) {

        AiChatResponse response = ownerAiAssistantService.chat(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * GET /api/v1/owner/ai-assistant/companies/autocomplete
     *
     * Autocomplete canonical approved company suggestions for '@' mention trigger.
     */
    @GetMapping("/companies/autocomplete")
    @PreAuthorize("hasRole('BUSINESS_OWNER')")
    public ResponseEntity<ApiResponse<List<CompanyAutocompleteItemDto>>> autocompleteCompanies(
            @RequestParam(required = false, defaultValue = "") String q,
            @RequestParam(required = false, defaultValue = "10") int limit) {

        List<CompanyAutocompleteItemDto> results = ownerAiAssistantService.autocompleteCompanies(q, limit);
        return ResponseEntity.ok(ApiResponse.success(results));
    }
}
