package com.apms.domain.score.repository.sql;

import com.apms.domain.score.ScoreSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScoreSnapshotRepository extends JpaRepository<ScoreSnapshot, Long> {
    List<ScoreSnapshot> findByCompanyIdAndEvaluatedRoleIsNullOrderByCreatedAtDesc(String companyId);

    List<ScoreSnapshot> findTop10ByEvaluatedRoleIsNullOrderByCreatedAtDesc();

    // Canonical queries
    List<ScoreSnapshot> findByTargetCompanyProfileIdAndEvaluatedRoleIsNotNullOrderByCalculatedAtDesc(String targetCompanyProfileId);

    List<ScoreSnapshot> findByTargetCompanyProfileIdAndEvaluatedRoleOrderByCalculatedAtDesc(String targetCompanyProfileId, com.apms.domain.company.enums.CompanyRole evaluatedRole);

    // Phase 2B Idempotency lookups
    java.util.Optional<ScoreSnapshot> findBySourceEvaluationDraftId(String sourceEvaluationDraftId);

    java.util.Optional<ScoreSnapshot> findByApprovalIdempotencyKey(String approvalIdempotencyKey);
}
