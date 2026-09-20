package com.apms.domain.profile.assessment.dto;

import com.apms.domain.profile.assessment.RelationshipAssessmentType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RelationshipAssessmentDraftResponse {

    private Long draftId;
    private String companyProfileId;
    private Long actorAccountId;
    private String actorRole;
    private RelationshipAssessmentType draftType;

    // Lineage & Base Info
    private Long baseOfficialAssessmentId;
    private Integer baseMajorVersion;
    private Integer baseMinorRevision;
    private String baseFormattedVersion;

    // Latest Official Snapshot for Lineage Detection
    private Long latestOfficialAssessmentId;
    private Integer latestOfficialMajorVersion;
    private Integer latestOfficialMinorRevision;
    private String latestOfficialFormattedVersion;

    /**
     * Stale flag:
     * - For Owner: true if baseOfficialAssessmentId does not match latest official assessment.
     * - For Manager: true if baseMajorVersion is less than latest official major version.
     */
    private Boolean isStale;

    /**
     * Informational base updated flag (for Manager):
     * True if baseOfficialAssessmentId != latestOfficialAssessmentId, but within same major.
     */
    private Boolean isBaseUpdated;

    private Integer completedCriteriaCount;
    private Integer changedCriterionCount;

    // Manager / Baseline Criterion Scores (0-5)
    private Integer commercialScore;
    private Integer cooperationScore;
    private Integer strategicScore;
    private Integer relationshipNetworkScore;
    private Integer engagementScore;
    private Integer qualitativeScore;

    // Owner Explicit Adjusted Scores
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

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
