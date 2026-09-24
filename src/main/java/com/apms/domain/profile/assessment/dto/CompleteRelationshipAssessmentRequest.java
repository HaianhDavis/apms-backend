package com.apms.domain.profile.assessment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompleteRelationshipAssessmentRequest {
    private Long sourceAssessmentId;
    private Integer baseMajorVersion;
    private Integer commercialAwardedScore;
    private String commercialAdjustmentReason;
    private String commercialEvidenceNote;
    private Integer cooperationScore;
    private String cooperationEvidenceNote;
    private Integer strategicScore;
    private String strategicEvidenceNote;
    private Integer relationshipNetworkScore;
    private String relationshipNetworkNote;
    private Integer engagementScore;
    private String engagementEvidenceNote;
    private Integer qualitativeScore;
    private String qualitativeEvidenceNote;
    private Integer trustScore;
    private String trustEvidenceNote;
    private String managerNote;
}
