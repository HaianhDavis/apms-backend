package com.apms.domain.score.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ScoreRuleDto {
    private Long id;
    private String ruleName;
    private String ruleCategory;
    private Integer weight;
    private String ruleConditionJson;
    private Boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
