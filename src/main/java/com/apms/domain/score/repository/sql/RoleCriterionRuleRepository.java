package com.apms.domain.score.repository.sql;

import com.apms.domain.score.RoleCriterionRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RoleCriterionRuleRepository extends JpaRepository<RoleCriterionRule, Long> {

    List<RoleCriterionRule> findByRuleSetIdAndActiveTrueOrderByDisplayOrderAsc(Long ruleSetId);

    List<RoleCriterionRule> findByRuleSetIdOrderByDisplayOrderAsc(Long ruleSetId);

    boolean existsByRuleSetIdAndCriterionKey(Long ruleSetId, String criterionKey);
}
