package com.apms.domain.score;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "score_snapshots")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScoreSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long scoreSnapshotId;

    @Column(length = 36)
    private String companyId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private com.apms.domain.project.Project project;

    public String getProjectId() {
        return project != null ? String.valueOf(project.getId()) : null;
    }

    @Column
    private String candidateId;

    private Integer partnerFitScore;

    private Integer competitionLevel;

    private Integer riskLevel;

    private Integer relationshipStrength;

    private Integer totalScore;

    @Column(columnDefinition = "NVARCHAR(MAX)") // or TEXT depending on SQL Server mapping, NVARCHAR(MAX) is standard
    private String factorsJson;

    @Column
    private String ruleVersion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "generated_by_account_id", nullable = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private com.apms.domain.user.Account generatedByAccount;

    public String getGeneratedBy() {
        return generatedByAccount != null ? String.valueOf(generatedByAccount.getId()) : "SYSTEM";
    }

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    // --- Canonical Role-Scoring Fields (Phase 1) ---

    @Column(name = "target_company_profile_id")
    private String targetCompanyProfileId;

    @Column(name = "target_profile_version")
    private Integer targetProfileVersion;

    @Column(name = "reference_company_profile_id")
    private String referenceCompanyProfileId;

    @Column(name = "reference_profile_version")
    private Integer referenceProfileVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "evaluated_role")
    private com.apms.domain.company.enums.CompanyRole evaluatedRole;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_score_rule_set_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private RoleScoreRuleSet roleScoreRuleSet;

    @Column(name = "score_rule_set_version")
    private String scoreRuleSetVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "weighting_method")
    private com.apms.domain.score.enums.WeightingMethod weightingMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "weight_source")
    private com.apms.domain.score.enums.WeightSource weightSource;

    @Column(name = "weight_version")
    private String weightVersion;

    @Column(name = "criterion_scores_json", columnDefinition = "NVARCHAR(MAX)")
    private String criterionScoresJson;

    @Column(name = "normalized_criterion_scores_json", columnDefinition = "NVARCHAR(MAX)")
    private String normalizedCriterionScoresJson;

    @Column(name = "weights_used_json", columnDefinition = "NVARCHAR(MAX)")
    private String weightsUsedJson;

    @Column(name = "overall_score", precision = 5, scale = 2)
    private java.math.BigDecimal overallScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "completeness_status")
    private com.apms.domain.score.enums.EvaluationCompletenessStatus completenessStatus;

    @Column(name = "missing_criteria_json", columnDefinition = "NVARCHAR(MAX)")
    private String missingCriteriaJson;

    @Column(name = "evidence_refs_json", columnDefinition = "NVARCHAR(MAX)")
    private String evidenceRefsJson;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "calculated_by_account_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private com.apms.domain.user.Account calculatedByAccount;

    @Column(name = "calculated_at")
    private java.time.Instant calculatedAt;

    // --- Phase 2B Cross-DB Approval Idempotency ---
    @Column(name = "source_evaluation_draft_id")
    private String sourceEvaluationDraftId;

    @Column(name = "approval_idempotency_key")
    private String approvalIdempotencyKey;

    // --- Phase 2C.9A Cross-Role Traceability Hardening ---
    @Column(name = "approved_role_evaluation_version_id")
    private String approvedRoleEvaluationVersionId;

    @Column(name = "approved_role_evaluation_version_number")
    private Integer approvedRoleEvaluationVersionNumber;
}
