package com.apms.domain.score.repository.sql;

import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.score.RoleScoreRuleSet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RoleScoreRuleSetRepository extends JpaRepository<RoleScoreRuleSet, Long> {

    Optional<RoleScoreRuleSet> findByEvaluatedRoleAndActiveTrue(CompanyRole evaluatedRole);

    Optional<RoleScoreRuleSet> findByEvaluatedRoleAndRuleSetVersion(CompanyRole evaluatedRole, String ruleSetVersion);

    boolean existsByEvaluatedRoleAndRuleSetVersion(CompanyRole evaluatedRole, String ruleSetVersion);
}
