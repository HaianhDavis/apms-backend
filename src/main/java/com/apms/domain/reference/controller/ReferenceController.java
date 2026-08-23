package com.apms.domain.reference.controller;

import com.apms.common.enums.ProjectKeyResultType;
import com.apms.common.enums.RelationshipType;
import com.apms.common.response.ApiResponse;
import com.apms.domain.reference.dto.KeyResultReferenceResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/reference")
public class ReferenceController {

    @GetMapping("/key-results")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<ApiResponse<List<KeyResultReferenceResponse>>> getKeyResults() {
        List<KeyResultReferenceResponse> response = Arrays.stream(ProjectKeyResultType.values())
                .map(type -> KeyResultReferenceResponse.builder()
                        .type(type)
                        .displayName(type.getDisplayName())
                        .description(type.getDescription())
                        .supportedRelationshipTypes(getSupportedRelationships(type))
                        .build())
                .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    private List<RelationshipType> getSupportedRelationships(ProjectKeyResultType type) {
        if (type == ProjectKeyResultType.CONTRACT_INFORMATION) {
            return List.of(RelationshipType.PARTNER_WITH, RelationshipType.CUSTOMER_OF, RelationshipType.SUPPLIER_OF);
        }
        // Empty list means no specific restriction
        return List.of();
    }
}
