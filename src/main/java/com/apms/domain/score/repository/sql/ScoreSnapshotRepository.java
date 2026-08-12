package com.apms.domain.score.repository.sql;

import com.apms.domain.score.ScoreSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ScoreSnapshotRepository extends JpaRepository<ScoreSnapshot, Long> {
    List<ScoreSnapshot> findByCompanyIdAndEvaluatedRoleIsNullOrderByCreatedAtDesc(String companyId);

    List<ScoreSnapshot> findTop10ByEvaluatedRoleIsNullOrderByCreatedAtDesc();

    // Canonical queries
    List<ScoreSnapshot> findByTargetCompanyProfileIdAndEvaluatedRoleIsNotNullOrderByCalculatedAtDesc(String targetCompanyProfileId);

    List<ScoreSnapshot> findByTargetCompanyProfileIdAndEvaluatedRoleOrderByCalculatedAtDesc(String targetCompanyProfileId, com.apms.domain.company.enums.CompanyRole evaluatedRole);

    Optional<ScoreSnapshot> findFirstByTargetCompanyProfileIdAndEvaluatedRoleAndEvaluatorRoleAndAuthoritativeTrueOrderByCalculatedAtDescCreatedAtDesc(
            String targetCompanyProfileId,
            com.apms.domain.company.enums.CompanyRole evaluatedRole,
            com.apms.common.enums.SystemRole evaluatorRole);

    Optional<ScoreSnapshot> findFirstByTargetCompanyProfileIdAndEvaluatedRoleAndEvaluatorRoleOrderByCalculatedAtDescCreatedAtDesc(
            String targetCompanyProfileId,
            com.apms.domain.company.enums.CompanyRole evaluatedRole,
            com.apms.common.enums.SystemRole evaluatorRole);

    @Query("""
            select s from ScoreSnapshot s
            where s.evaluatedRole is not null
              and (
                    s.authoritative = true
                    or not exists (
                        select 1 from ScoreSnapshot owner
                        where owner.targetCompanyProfileId = s.targetCompanyProfileId
                          and owner.evaluatedRole = s.evaluatedRole
                          and owner.evaluatorRole = com.apms.common.enums.SystemRole.BUSINESS_OWNER
                          and owner.authoritative = true
                    )
                  )
            order by s.calculatedAt desc, s.createdAt desc
            """)
    List<ScoreSnapshot> findEffectiveCanonicalSnapshotsOrderByCalculatedAtDesc();

    // Phase 2B Idempotency lookups
    java.util.Optional<ScoreSnapshot> findBySourceEvaluationDraftId(String sourceEvaluationDraftId);

    java.util.Optional<ScoreSnapshot> findByApprovalIdempotencyKey(String approvalIdempotencyKey);
}
