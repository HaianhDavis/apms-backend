package com.apms.domain.score.draft;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AutomaticSuggestion {

    private String criterionKey;
    
    private BigDecimal suggestedRawScore;
    
    @Builder.Default
    private LinkedHashMap<String, BigDecimal> componentScores = new LinkedHashMap<>();
    
    @Builder.Default
    private LinkedHashMap<String, BigDecimal> componentWeights = new LinkedHashMap<>();
    
    private BigDecimal componentCoverage;
    
    @Builder.Default
    private List<String> missingComponents = new ArrayList<>();
    
    @Builder.Default
    private List<String> calculationWarnings = new ArrayList<>();
    
    private String suggestionRationale;
    
    private String rubricVersion;
    
    private LocalDateTime generatedAt;
    
    @Builder.Default
    private Boolean accepted = false;
    
    private Long acceptedByAccountId;
    private LocalDateTime acceptedAt;
}
