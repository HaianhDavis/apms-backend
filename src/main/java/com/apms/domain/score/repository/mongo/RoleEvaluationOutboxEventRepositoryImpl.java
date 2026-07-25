package com.apms.domain.score.repository.mongo;

import com.apms.common.enums.OutboxEventStatus;
import com.apms.domain.score.outbox.RoleEvaluationOutboxEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;
import com.mongodb.client.result.UpdateResult;

@RequiredArgsConstructor
public class RoleEvaluationOutboxEventRepositoryImpl implements RoleEvaluationOutboxEventRepositoryCustom {

    private final MongoTemplate mongoTemplate;

    @Override
    public Optional<RoleEvaluationOutboxEvent> claimNextEvent(String workerId, LocalDateTime currentTime, LocalDateTime leaseUntil) {
        Query query = new Query();
        query.addCriteria(
            new Criteria().orOperator(
                Criteria.where("status").in(OutboxEventStatus.PENDING, OutboxEventStatus.RETRY)
                    .andOperator(new Criteria().orOperator(
                        Criteria.where("nextAttemptAt").exists(false),
                        Criteria.where("nextAttemptAt").lte(currentTime)
                    )),
                Criteria.where("status").is(OutboxEventStatus.PROCESSING)
                    .and("lockedAt").lte(currentTime)
            )
        );

        // Deterministic ordering by createdAt (or nextAttemptAt)
        query.with(Sort.by(Sort.Direction.ASC, "createdAt"));

        Update update = new Update()
                .set("status", OutboxEventStatus.PROCESSING)
                .set("lockedBy", workerId)
                .set("lockedAt", leaseUntil)
                .inc("attemptCount", 1);

        FindAndModifyOptions options = new FindAndModifyOptions().returnNew(true);

        RoleEvaluationOutboxEvent claimed = mongoTemplate.findAndModify(query, update, options, RoleEvaluationOutboxEvent.class);
        return Optional.ofNullable(claimed);
    }

    private void finalizeEvent(String id, String workerId, Update casUpdate) {
        Query casQuery = new Query(Criteria.where("id").is(id)
                .and("status").is(OutboxEventStatus.PROCESSING)
                .and("lockedBy").is(workerId));

        UpdateResult result = mongoTemplate.updateFirst(casQuery, casUpdate, RoleEvaluationOutboxEvent.class);
        if (result.getModifiedCount() == 0) {
            throw new OptimisticLockingFailureException("Lost ownership of event " + id);
        }
    }

    @Override
    public void finalizeAsProcessed(String eventId, String workerId) {
        Update update = new Update()
                .set("status", OutboxEventStatus.PROCESSED)
                .set("processedAt", LocalDateTime.now())
                .unset("lastError")
                .unset("lockedAt")
                .unset("lockedBy");
        finalizeEvent(eventId, workerId, update);
    }

    @Override
    public void finalizeAsRetry(String eventId, String workerId, LocalDateTime nextAttempt, String lastError) {
        Update update = new Update()
                .set("status", OutboxEventStatus.RETRY)
                .set("nextAttemptAt", nextAttempt)
                .set("lastError", lastError)
                .unset("lockedAt")
                .unset("lockedBy");
        finalizeEvent(eventId, workerId, update);
    }

    @Override
    public void finalizeAsDeadLetter(String eventId, String workerId, String lastError) {
        Update update = new Update()
                .set("status", OutboxEventStatus.DEAD_LETTER)
                .set("lastError", lastError)
                .unset("lockedAt")
                .unset("lockedBy");
        finalizeEvent(eventId, workerId, update);
    }
}
