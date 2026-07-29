package com.apms.domain.score.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ScoreSnapshotDto {
    private Long scoreSnapshotId;
    private String companyId;
    private String projectId;
    private String candidateId;
    private Integer partnerFitScore;
    private Integer competitionLevel;
    private Integer riskLevel;
    private Integer relationshipStrength;
    private Integer totalScore;
    private String factorsJson;
    private String ruleVersion;
    private String generatedBy;
    private LocalDateTime createdAt;
}
