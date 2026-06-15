package com.apms.domain.score.repository.sql;

import com.apms.domain.score.ScoreSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScoreSnapshotRepository extends JpaRepository<ScoreSnapshot, Long> {
    List<ScoreSnapshot> findByCompanyIdOrderByCreatedAtDesc(String companyId);
    List<ScoreSnapshot> findTop10ByOrderByCreatedAtDesc();
}
