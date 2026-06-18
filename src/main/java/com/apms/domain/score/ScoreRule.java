package com.apms.domain.score;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "score_rules")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScoreRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String ruleName;

    @Column(nullable = false)
    private String ruleCategory; // e.g., PARTNER_FIT, COMPETITION, RISK, RELATIONSHIP

    @Column(nullable = false)
    private Integer weight;

    @Column(length = 1000)
    private String ruleConditionJson;

    private Boolean isActive;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private com.apms.domain.user.Account createdByAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private com.apms.domain.project.Project project;

    public Long getCreatedByAccountId() {
        return createdByAccount != null ? createdByAccount.getId() : null;
    }

    public Long getProjectId() {
        return project != null ? project.getId() : null;
    }

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
