package com.apms.domain.monitoring.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Relationship Changes", description = "Stub endpoints for relationship changes and history")
public class CompanyRelationshipChangeStubController {

    @GetMapping("/company-profiles/{companyProfileId}/relationship-history")
    @Operation(summary = "Get relationship history (Stub)")
    public ResponseEntity<List<Object>> getRelationshipHistory(@PathVariable String companyProfileId) {
        return ResponseEntity.ok(Collections.emptyList());
    }

//    @GetMapping("/company-profiles/{companyProfileId}/relationship-changes/pending")
//    @Operation(summary = "Get pending relationship changes (Stub)")
//    public ResponseEntity<List<Object>> getPendingProposals(@PathVariable String companyProfileId) {
//        return ResponseEntity.ok(Collections.emptyList());
//    }
}
