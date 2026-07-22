package com.apms.domain.score.repository.mongo;

import com.apms.domain.score.draft.RoleEvaluationVersion;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RoleEvaluationVersionRepository extends MongoRepository<RoleEvaluationVersion, String> {
    
    Optional<RoleEvaluationVersion> findByEvaluationIdAndVersionNumber(String evaluationId, Integer versionNumber);
    
    List<RoleEvaluationVersion> findByEvaluationIdOrderByVersionNumberDesc(String evaluationId);
    
    // Explicitly do not define any custom update or delete methods
    // to enforce immutability at the domain level.
}
