package com.apms.domain.profile.assessment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompleteOwnerAdjustmentRequest {
    private Long sourceAssessmentId;
    private Integer baseMajorVersion;
    private Integer baseMinorRevision;
    private Integer ownerCommercialScore;
    private String ownerCommercialNote;
    private Integer ownerCooperationScore;
    private String ownerCooperationNote;
    private Integer ownerStrategicScore;
    private String ownerStrategicNote;
    private Integer ownerRelationshipNetworkScore;
    private String ownerRelationshipNetworkNote;
    private Integer ownerEngagementScore;
    private String ownerEngagementNote;
    private Integer ownerQualitativeScore;
    private String ownerQualitativeNote;
    private String ownerAdjustmentReason;
    private String ownerNote;
}
