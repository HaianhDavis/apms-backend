package com.apms.domain.score.repository.mongo;

import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface RoleEvaluationDraftRepository extends MongoRepository<RoleEvaluationDraft, String> {

    List<RoleEvaluationDraft> findByProjectIdAndTaskIdOrderByCreatedAtDesc(Long projectId, Long taskId);

    List<RoleEvaluationDraft> findByTargetCompanyIdAndEvaluatedRoleOrderByCreatedAtDesc(String targetCompanyId, CompanyRole evaluatedRole);

    Optional<RoleEvaluationDraft> findByActiveDraftKey(String activeDraftKey);

    boolean existsByActiveDraftKey(String activeDraftKey);

    Optional<RoleEvaluationDraft> findByApprovedSnapshotId(Long approvedSnapshotId);

    Optional<RoleEvaluationDraft> findByApprovalIdempotencyKey(String approvalIdempotencyKey);
}
