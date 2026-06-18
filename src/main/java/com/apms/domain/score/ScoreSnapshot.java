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

    @Column(nullable = false, length = 36)
    private String companyId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private com.apms.domain.project.Project project;

    public String getProjectId() {
        return project != null ? String.valueOf(project.getId()) : null;
    }

    @Column(nullable = false)
    private String candidateId;

    private Integer partnerFitScore;
    
    private Integer competitionLevel;
    
    private Integer riskLevel;
    
    private Integer relationshipStrength;
    
    private Integer totalScore;

    @Column(columnDefinition = "NVARCHAR(MAX)") // or TEXT depending on SQL Server mapping, NVARCHAR(MAX) is standard
    private String factorsJson;

    @Column(nullable = false)
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
}
