package com.apms.domain.score;

import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.score.enums.WeightSource;
import com.apms.domain.score.enums.WeightingMethod;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "role_score_rule_sets", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"evaluated_role", "rule_set_version"})
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoleScoreRuleSet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "evaluated_role", nullable = false)
    private CompanyRole evaluatedRole;

    @Column(name = "rule_set_version", nullable = false)
    private String ruleSetVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "weighting_method", nullable = false)
    private WeightingMethod weightingMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "weight_source", nullable = false)
    private WeightSource weightSource;

    @Column(name = "weight_version", nullable = false)
    private String weightVersion;

    @Column(name = "is_active", nullable = false)
    private Boolean active;

    @Column(name = "effective_from")
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Column(name = "created_by_account_id")
    private Long createdByAccountId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    @OneToMany(mappedBy = "ruleSet", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<RoleCriterionRule> rules = new ArrayList<>();
}
