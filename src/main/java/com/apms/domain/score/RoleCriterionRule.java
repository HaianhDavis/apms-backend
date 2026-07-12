package com.apms.domain.score;

import com.apms.domain.score.enums.ScoreDirection;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "role_criterion_rules", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"rule_set_id", "criterion_key"})
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoleCriterionRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_set_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private RoleScoreRuleSet ruleSet;

    @Column(name = "criterion_key", nullable = false)
    private String criterionKey;

    @Column(name = "criterion_name", nullable = false)
    private String criterionName;

    @Column(name = "weight", nullable = false, precision = 8, scale = 6)
    private BigDecimal weight;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false)
    private ScoreDirection direction;

    @Column(name = "is_required", nullable = false)
    private Boolean required;

    @Column(name = "scoring_method")
    private String scoringMethod;

    @Column(name = "rule_definition_json", columnDefinition = "NVARCHAR(MAX)")
    private String ruleDefinitionJson;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    @Column(name = "is_active", nullable = false)
    private Boolean active;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
