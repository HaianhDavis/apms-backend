package com.apms.domain.profile.assessment;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "company_relationship_assessments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CompanyRelationshipAssessment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_company_profile_id", nullable = false, length = 255)
    private String ownerCompanyProfileId;

    @Column(name = "company_profile_id", nullable = false, length = 255)
    private String companyProfileId;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private RelationshipAssessmentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "assessment_type", length = 30)
    @Builder.Default
    private RelationshipAssessmentType assessmentType = RelationshipAssessmentType.MANAGER_ASSESSMENT;

    @Column(name = "source_assessment_id")
    private Long sourceAssessmentId;

    @Column(name = "scoring_policy_version", nullable = false, length = 50)
    @Builder.Default
    private String scoringPolicyVersion = "RELATIONSHIP_CLOSENESS_V5";

    // Commercial Evidence Snapshot (Frozen once submitted)
    @Column(name = "commercial_score")
    private Integer commercialScore;

    @Column(name = "commercial_suggested_score")
    private Integer commercialSuggestedScore;

    @Column(name = "commercial_awarded_score")
    private Integer commercialAwardedScore;

    @Column(name = "commercial_adjustment_reason", length = 1000)
    private String commercialAdjustmentReason;

    @Column(name = "commercial_evidence_note", length = 1000)
    private String commercialEvidenceNote;

    @Column(name = "contract_value_score")
    private Integer contractValueScore;

    @Column(name = "contract_count_score", nullable = false)
    @Builder.Default
    private Integer contractCountScore = 0;

    @Column(name = "relationship_duration_score", nullable = false)
    @Builder.Default
    private Integer relationshipDurationScore = 0;

    @Column(name = "contract_recency_score", nullable = false)
    @Builder.Default
    private Integer contractRecencyScore = 0;

    @Column(name = "approved_contract_count", nullable = false)
    @Builder.Default
    private Integer approvedContractCount = 0;

    @Column(name = "total_contract_value_vnd", precision = 18, scale = 2)
    private BigDecimal totalContractValueVnd;

    @Column(name = "contract_currencies", length = 200)
    private String contractCurrencies;

    @Column(name = "currency_breakdown", length = 1000)
    private String currencyBreakdown;

    @Column(name = "contract_value_status", nullable = false, length = 30)
    @Builder.Default
    private String contractValueStatus = "SCORABLE";

    @Column(name = "first_cooperation_date")
    private LocalDate firstCooperationDate;

    @Column(name = "latest_contract_date")
    private LocalDate latestContractDate;

    @Column(name = "upcoming_contract_count", nullable = false)
    @Builder.Default
    private Integer upcomingContractCount = 0;

    // Normalization Metadata
    @Column(name = "scorable_base", nullable = false)
    @Builder.Default
    private Integer scorableBase = 100;

    @Column(name = "normalization_applied", nullable = false)
    @Builder.Default
    private Boolean normalizationApplied = false;

    // Manager Human Assessment
    @Column(name = "cooperation_score")
    private Integer cooperationScore;

    @Column(name = "cooperation_evidence_note", length = 1000)
    private String cooperationEvidenceNote;

    @Column(name = "strategic_score")
    private Integer strategicScore;

    @Column(name = "strategic_evidence_note", length = 1000)
    private String strategicEvidenceNote;

    @Column(name = "relationship_network_score")
    private Integer relationshipNetworkScore;

    @Column(name = "relationship_network_note", length = 500)
    private String relationshipNetworkNote;

    @Column(name = "engagement_score")
    private Integer engagementScore;

    @Column(name = "engagement_evidence_note", length = 1000)
    private String engagementEvidenceNote;

    @Column(name = "qualitative_score")
    private Integer qualitativeScore;

    @Column(name = "qualitative_evidence_note", length = 1000)
    private String qualitativeEvidenceNote;

    @Column(name = "manager_note", length = 2000)
    private String managerNote;

    @Column(name = "manager_raw_scorable_score")
    private Integer managerRawScorableScore;

    @Column(name = "manager_total_score")
    private Integer managerTotalScore;

    @Column(name = "manager_rank", length = 5)
    private String managerRank;

    @Column(name = "manager_account_id")
    private Long managerAccountId;

    @Column(name = "manager_submitted_at")
    private LocalDateTime managerSubmittedAt;

    // Changes Requested
    @Column(name = "changes_requested_reason", length = 2000)
    private String changesRequestedReason;

    @Column(name = "changes_requested_by_account_id")
    private Long changesRequestedByAccountId;

    @Column(name = "changes_requested_at")
    private LocalDateTime changesRequestedAt;

    // Owner Final Assessment & Overrides
    @Column(name = "owner_commercial_score")
    private Integer ownerCommercialScore;

    @Column(name = "owner_cooperation_score")
    private Integer ownerCooperationScore;

    @Column(name = "owner_strategic_score")
    private Integer ownerStrategicScore;

    @Column(name = "owner_relationship_network_score")
    private Integer ownerRelationshipNetworkScore;

    @Column(name = "owner_relationship_network_note", length = 500)
    private String ownerRelationshipNetworkNote;

    @Column(name = "owner_engagement_score")
    private Integer ownerEngagementScore;

    @Column(name = "owner_qualitative_score")
    private Integer ownerQualitativeScore;

    @Column(name = "owner_note", length = 2000)
    private String ownerNote;

    @Column(name = "owner_adjustment_reason", length = 2000)
    private String ownerAdjustmentReason;

    @Column(name = "owner_raw_scorable_score")
    private Integer ownerRawScorableScore;

    @Column(name = "owner_final_total_score")
    private Integer ownerFinalTotalScore;

    @Column(name = "owner_final_rank", length = 5)
    private String ownerFinalRank;

    @Column(name = "owner_account_id")
    private Long ownerAccountId;

    @Column(name = "finalized_at")
    private LocalDateTime finalizedAt;

    // Audit & Optimistic Lock
    @Column(name = "created_by_account_id", nullable = false)
    private Long createdByAccountId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
