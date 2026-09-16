package com.apms.domain.profile.assessment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinalizeRelationshipAssessmentRequest {
    private Integer ownerCommercialScore;
    private Integer ownerCooperationScore;
    private Integer ownerStrategicScore;
    private Integer ownerRelationshipNetworkScore;
    private String ownerRelationshipNetworkNote;
    private Integer ownerEngagementScore;
    private Integer ownerQualitativeScore;
    private Integer ownerTrustScore;
    private String ownerNote;
    private String ownerAdjustmentReason;
}
