package com.apms.domain.score.controller;

import com.apms.common.response.ApiResponse;
import com.apms.domain.score.dto.ScoreRuleDto;
import com.apms.domain.score.dto.ScoreSnapshotDto;
import com.apms.domain.score.service.ScoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ScoreController {

    private final ScoreService scoreService;

    // ─────────────────────────────────────────────
    // SCORE SNAPSHOTS
    // ─────────────────────────────────────────────

    @GetMapping("/profiles/{companyId}/scores")
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'RESEARCH_STAFF')")
    public ResponseEntity<ApiResponse<List<ScoreSnapshotDto>>> getCompanyScores(@PathVariable String companyId) {
        return ResponseEntity.ok(ApiResponse.success(scoreService.getCompanyScores(companyId)));
    }

    // ─────────────────────────────────────────────
    // SCORE RULES
    // ─────────────────────────────────────────────

    @GetMapping("/score-rules")
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<ApiResponse<List<ScoreRuleDto>>> getAllRules() {
        return ResponseEntity.ok(ApiResponse.success(scoreService.getAllRules()));
    }

    @PostMapping("/score-rules")
    @PreAuthorize("hasRole('BUSINESS_OWNER')")
    public ResponseEntity<ApiResponse<ScoreRuleDto>> createRule(@RequestBody ScoreRuleDto request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(scoreService.createRule(request), "Score rule created"));
    }

    @PutMapping("/score-rules/{id}")
    @PreAuthorize("hasRole('BUSINESS_OWNER')")
    public ResponseEntity<ApiResponse<ScoreRuleDto>> updateRule(@PathVariable Long id, @RequestBody ScoreRuleDto request) {
        return ResponseEntity.ok(ApiResponse.success(scoreService.updateRule(id, request), "Score rule updated"));
    }

    @DeleteMapping("/score-rules/{id}")
    @PreAuthorize("hasRole('BUSINESS_OWNER')")
    public ResponseEntity<ApiResponse<Void>> deleteRule(@PathVariable Long id) {
        scoreService.deleteRule(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Score rule deleted"));
    }
}
