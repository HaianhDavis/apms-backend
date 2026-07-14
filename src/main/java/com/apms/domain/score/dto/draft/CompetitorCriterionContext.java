package com.apms.domain.score.dto.draft;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class CompetitorCriterionContext {
    private String criterionKey;
    private LocalDate periodStart;
    private LocalDate periodEnd;

    @Builder.Default
    private Map<String, Object> referenceFacts = new java.util.HashMap<>();

    @Builder.Default
    private Map<String, Object> targetFacts = new java.util.HashMap<>();

    @Builder.Default
    private List<Map<String, Object>> externalSignals = new java.util.ArrayList<>();

    @Builder.Default
    private List<Map<String, Object>> draftEvidence = new java.util.ArrayList<>();
}
