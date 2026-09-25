package com.apms.domain.profile.assessment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SaveRelationshipAssessmentDraftRequest {

    private Long baseOfficialAssessmentId;
    private Integer baseMajorVersion;
    private Integer baseMinorRevision;

    // Manager / Baseline Criterion Scores (0-5)
    private Integer commercialScore;
    private Integer cooperationScore;
    private Integer strategicScore;
    private Integer relationshipNetworkScore;
    private Integer engagementScore;
    private Integer qualitativeScore;

    // Owner Explicit Adjusted Scores (null if untouched)
    private Integer ownerCommercialScore;
    private Integer ownerCooperationScore;
    private Integer ownerStrategicScore;
    private Integer ownerRelationshipNetworkScore;
    private Integer ownerEngagementScore;
    private Integer ownerQualitativeScore;

    // Manager Evidence Notes
    private String commercialEvidenceNote;
    private String cooperationEvidenceNote;
    private String strategicEvidenceNote;
    private String relationshipNetworkNote;
    private String engagementEvidenceNote;
    private String qualitativeEvidenceNote;
    private String managerNote;

    // Owner Criterion Notes
    private String ownerCommercialNote;
    private String ownerCooperationNote;
    private String ownerStrategicNote;
    private String ownerRelationshipNetworkNote;
    private String ownerEngagementNote;
    private String ownerQualitativeNote;
    private String ownerNote;
    private String ownerAdjustmentReason;
}
