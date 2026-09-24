package com.apms.domain.profile.assessment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OwnerAdjustmentUpdateRequest {
    private Integer ownerCommercialScore;
    private Integer ownerCooperationScore;
    private Integer ownerStrategicScore;
    private Integer ownerRelationshipNetworkScore;
    private String ownerRelationshipNetworkNote;
    private Integer ownerEngagementScore;
    private Integer ownerQualitativeScore;
    private String ownerAdjustmentReason;
    private String ownerNote;
}
