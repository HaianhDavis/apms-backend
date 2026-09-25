package com.apms.domain.profile.assessment;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Nationalized;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "company_relationship_assessment_drafts", uniqueConstraints = {
        @UniqueConstraint(name = "uq_rel_assessment_draft_actor",
                columnNames = {"owner_company_profile_id", "company_profile_id", "actor_account_id", "draft_type"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RelationshipAssessmentDraft {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_company_profile_id", nullable = false, length = 255)
    private String ownerCompanyProfileId;

    @Column(name = "company_profile_id", nullable = false, length = 255)
    private String companyProfileId;

    @Column(name = "actor_account_id", nullable = false)
    private Long actorAccountId;

    @Column(name = "actor_role", nullable = false, length = 50)
    private String actorRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "draft_type", nullable = false, length = 30)
    private RelationshipAssessmentType draftType;

    @Column(name = "base_official_assessment_id")
    private Long baseOfficialAssessmentId;

    @Column(name = "base_major_version")
    private Integer baseMajorVersion;

    @Column(name = "base_minor_revision")
    private Integer baseMinorRevision;

    // Manager / Baseline Criterion Scores (0-5)
    @Column(name = "commercial_score")
    private Integer commercialScore;

    @Column(name = "cooperation_score")
    private Integer cooperationScore;

    @Column(name = "strategic_score")
    private Integer strategicScore;

    @Column(name = "relationship_network_score")
    private Integer relationshipNetworkScore;

    @Column(name = "engagement_score")
    private Integer engagementScore;

    @Column(name = "qualitative_score")
    private Integer qualitativeScore;

    // Owner Explicit Adjusted Scores (null if untouched from baseline)
    @Column(name = "owner_commercial_score")
    private Integer ownerCommercialScore;

    @Column(name = "owner_cooperation_score")
    private Integer ownerCooperationScore;

    @Column(name = "owner_strategic_score")
    private Integer ownerStrategicScore;

    @Column(name = "owner_relationship_network_score")
    private Integer ownerRelationshipNetworkScore;

    @Column(name = "owner_engagement_score")
    private Integer ownerEngagementScore;

    @Column(name = "owner_qualitative_score")
    private Integer ownerQualitativeScore;

    // Manager Evidence Notes
    @Nationalized
    @Column(name = "commercial_evidence_note", length = 1000)
    private String commercialEvidenceNote;

    @Nationalized
    @Column(name = "cooperation_evidence_note", length = 1000)
    private String cooperationEvidenceNote;

    @Nationalized
    @Column(name = "strategic_evidence_note", length = 1000)
    private String strategicEvidenceNote;

    @Nationalized
    @Column(name = "relationship_network_note", length = 500)
    private String relationshipNetworkNote;

    @Nationalized
    @Column(name = "engagement_evidence_note", length = 1000)
    private String engagementEvidenceNote;

    @Nationalized
    @Column(name = "qualitative_evidence_note", length = 1000)
    private String qualitativeEvidenceNote;

    @Nationalized
    @Column(name = "manager_note", length = 2000)
    private String managerNote;

    // Owner Criterion Notes
    @Nationalized
    @Column(name = "owner_commercial_note", length = 1000)
    private String ownerCommercialNote;

    @Nationalized
    @Column(name = "owner_cooperation_note", length = 1000)
    private String ownerCooperationNote;

    @Nationalized
    @Column(name = "owner_strategic_note", length = 1000)
    private String ownerStrategicNote;

    @Nationalized
    @Column(name = "owner_relationship_network_note", length = 500)
    private String ownerRelationshipNetworkNote;

    @Nationalized
    @Column(name = "owner_engagement_note", length = 1000)
    private String ownerEngagementNote;

    @Nationalized
    @Column(name = "owner_qualitative_note", length = 1000)
    private String ownerQualitativeNote;

    @Nationalized
    @Column(name = "owner_note", length = 2000)
    private String ownerNote;

    @Nationalized
    @Column(name = "owner_adjustment_reason", length = 2000)
    private String ownerAdjustmentReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public String getBaseFormattedVersion() {
        if (baseMajorVersion == null) return null;
        return (baseMinorRevision != null && baseMinorRevision > 0)
                ? "V" + baseMajorVersion + "." + baseMinorRevision
                : "V" + baseMajorVersion;
    }
}
