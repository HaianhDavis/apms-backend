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

    @Column(nullable = false)
    private String projectId;

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

    @Column(nullable = false)
    private String generatedBy;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
