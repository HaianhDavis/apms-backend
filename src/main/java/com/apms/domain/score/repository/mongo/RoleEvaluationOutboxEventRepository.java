package com.apms.domain.score.repository.mongo;

import com.apms.domain.score.outbox.RoleEvaluationOutboxEvent;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RoleEvaluationOutboxEventRepository extends MongoRepository<RoleEvaluationOutboxEvent, String>, RoleEvaluationOutboxEventRepositoryCustom {
}
