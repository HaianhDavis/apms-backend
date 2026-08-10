package com.apms.domain.profile.closeness;

import com.apms.domain.profile.closeness.dto.RelationshipClosenessResponse;
import com.apms.domain.profile.closeness.dto.UpdateRelationshipClosenessRequest;
import com.apms.security.UserDetailsImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/company-profiles/{companyProfileId}/relationship-closeness")
@RequiredArgsConstructor
@Tag(name = "Relationship Closeness", description = "Manual 1-to-5 star relationship closeness rating")
public class CompanyRelationshipClosenessController {

    private final CompanyRelationshipClosenessService closenessService;

    @GetMapping
    @Operation(summary = "Get relationship closeness rating")
    public ResponseEntity<RelationshipClosenessResponse> getCloseness(
            @PathVariable String companyProfileId,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        return ResponseEntity.ok(closenessService.getCloseness(companyProfileId, currentUser));
    }

    @PutMapping
    @Operation(summary = "Create or update relationship closeness rating")
    public ResponseEntity<RelationshipClosenessResponse> updateCloseness(
            @PathVariable String companyProfileId,
            @Valid @RequestBody UpdateRelationshipClosenessRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        return ResponseEntity.ok(closenessService.updateCloseness(companyProfileId, request, currentUser));
    }

    @DeleteMapping
    @Operation(summary = "Delete relationship closeness rating")
    public ResponseEntity<Void> deleteCloseness(
            @PathVariable String companyProfileId,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        closenessService.deleteCloseness(companyProfileId, currentUser);
        return ResponseEntity.noContent().build();
    }
}
