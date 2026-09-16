package com.apms.domain.profile.assessment.dto;

import com.apms.domain.profile.assessment.RelationshipAssessmentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RelationshipAssessmentResponse {

    private Long id;
    private String companyProfileId;
    private String ownerCompanyProfileId;
    private Integer versionNumber;
    private RelationshipAssessmentStatus status;
    private com.apms.domain.profile.assessment.RelationshipAssessmentType assessmentType;
    private Long sourceAssessmentId;
    private Integer sourceVersionNumber;
    private Boolean isOwnerAdjustment;
    private Long createdByAccountId;
    private String scoringPolicyVersion;

    // Commercial Evidence Snapshot
    private Integer commercialScore;
    private Integer commercialSuggestedScore;
    private Integer commercialAwardedScore;
    private String commercialAdjustmentReason;
    private String commercialEvidenceNote;
    private Integer contractValueScore;
    private Integer contractCountScore;
    private Integer relationshipDurationScore;
    private Integer contractRecencyScore;
    private Integer approvedContractCount;
    private BigDecimal totalContractValueVnd;
    private String contractCurrencies;
    private String currencyBreakdown;
    private String contractValueStatus;
    private LocalDate firstCooperationDate;
    private LocalDate latestContractDate;
    private Integer upcomingContractCount;
    private String commercialSuggestionStatus; // COMPLETE, PARTIAL, UNAVAILABLE
    private Integer commercialAvailablePoints; // e.g. 50, 30

    // Normalization
    private Integer scorableBase;
    private Boolean normalizationApplied;

    // Manager Assessment
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
    private Integer managerRawScorableScore;
    private Integer managerTotalScore;
    private Double managerNormalizedScore;
    private String managerRank;
    private String managerRankDescription;
    private Long managerAccountId;
    private LocalDateTime managerSubmittedAt;

    // Changes Requested
    private String changesRequestedReason;
    private Long changesRequestedByAccountId;
    private LocalDateTime changesRequestedAt;

    // Owner Final Assessment & Overrides
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
    private Integer ownerRawScorableScore;
    private Integer ownerFinalTotalScore;
    private Double ownerNormalizedScore;
    private String ownerFinalRank;
    private String ownerFinalRankDescription;
    private Long ownerAccountId;
    private LocalDateTime finalizedAt;

    // Draft Completion State
    private Integer completedCriteriaCount;
    private Integer totalCriteriaCount; // 3 for V3, 6 for V1/V2
    private Integer draftSubtotalScore;
    private Boolean isComplete;

    // Official Snapshot Summary
    private Integer officialScore;
    private String officialRank;
    private String officialRankDescription;
    private Boolean isOfficialFinalized;

    // Permissions
    private Boolean canEditDraft;
    private Boolean canSubmit;
    private Boolean canComplete;
    private Boolean canRequestChanges;
    private Boolean canFinalize;
    private Boolean canCreateNewVersion;
    private Boolean canAdjust;
    private Boolean canCancelAdjustment;

    // Timestamps
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
