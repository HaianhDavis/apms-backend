package com.apms.domain.score.repository.sql;

import com.apms.domain.score.ScoreRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScoreRuleRepository extends JpaRepository<ScoreRule, Long> {
    List<ScoreRule> findByIsActiveTrue();
}
