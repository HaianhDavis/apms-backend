package com.apms.domain.score.outbox;

import com.apms.common.enums.OutboxEventStatus;
import com.apms.domain.score.repository.mongo.RoleEvaluationOutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class RoleEvaluationOutboxEventWorker {

    private final MongoTemplate mongoTemplate;
    private final RoleEvaluationOutboxEventRepository outboxEventRepository;
    private final RoleEvaluationOutboxEventProcessorDelegator delegator;
    private final List<RoleEvaluationOutboxEventProcessorStrategy> strategies;
    private final RoleEvaluationOutboxProperties properties;

    private final String workerId = UUID.randomUUID().toString();

    @Scheduled(fixedDelayString = "${apms.outbox.poll-delay:PT5S}")
    public void processEvents() {
        int batchSize = properties.getBatchSize();
        for (int i = 0; i < batchSize; i++) {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime leaseUntil = now.minus(properties.getLockLease());

            Query query = new Query();
            query.addCriteria(
                new Criteria().orOperator(
                    Criteria.where("status").in(OutboxEventStatus.PENDING, OutboxEventStatus.RETRY)
                        .andOperator(new Criteria().orOperator(
                            Criteria.where("nextAttemptAt").exists(false),
                            Criteria.where("nextAttemptAt").lte(now)
                        )),
                    Criteria.where("status").is(OutboxEventStatus.PROCESSING)
                        .and("lockedAt").lt(leaseUntil)
                )
            );

            Update update = new Update()
                .set("status", OutboxEventStatus.PROCESSING)
                .set("lockedAt", now)
                .set("lockedBy", workerId)
                .inc("attemptCount", 1);

            RoleEvaluationOutboxEvent event = mongoTemplate.findAndModify(
                query, update, new FindAndModifyOptions().returnNew(true), RoleEvaluationOutboxEvent.class
            );

            if (event == null) {
                break;
            }

            try {
                if (event.getPayloadHash() == null || event.getPayloadHash().isBlank()) {
                    throw new PayloadIntegrityException("Missing payload hash on outbox event. Legacy unhashed PENDING events are not supported by the strict integrity policy.");
                }

                String expectedHash = RoleEvaluationOutboxPayloadHasher.hash(event.getPayload());
                if (!event.getPayloadHash().equals(expectedHash)) {
                    throw new PayloadIntegrityException(String.format("Payload integrity failure. Expected: %s, Recomputed: %s", event.getPayloadHash(), expectedHash));
                }

                RoleEvaluationOutboxEventProcessorStrategy strategy = strategies.stream()
                        .filter(s -> s.supports(event.getEventType()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("No strategy found for event type: " + event.getEventType()));

                delegator.processEventWithIdempotency(event, strategy);

                // Mark as processed safely using CAS
                outboxEventRepository.finalizeAsProcessed(event.getId(), workerId);

            } catch (PayloadIntegrityException e) {
                log.error("Integrity failure for event {}", event.getEventId(), e);
                try {
                    outboxEventRepository.finalizeAsDeadLetter(event.getId(), workerId, e.getMessage());
                } catch (org.springframework.dao.OptimisticLockingFailureException casEx) {
                    log.warn("Lost ownership of event {} during integrity DEAD_LETTER transition", event.getEventId());
                }
            } catch (org.springframework.dao.OptimisticLockingFailureException e) {
                log.warn("Lost ownership of event {}", event.getEventId());
                // Do not attempt to retry or dead-letter, as we no longer own the event
            } catch (Exception e) {
                log.error("Error processing outbox event {}", event.getEventId(), e);

                OutboxEventStatus newStatus = event.getAttemptCount() >= properties.getMaxAttempts() ? OutboxEventStatus.DEAD_LETTER : OutboxEventStatus.RETRY;

                long retryDelaySeconds = properties.getInitialRetryDelay().getSeconds() * (long) Math.pow(2, event.getAttemptCount() - 1);
                if (retryDelaySeconds > properties.getMaximumRetryDelay().getSeconds()) {
                    retryDelaySeconds = properties.getMaximumRetryDelay().getSeconds();
                }
                LocalDateTime nextAttempt = LocalDateTime.now().plusSeconds(retryDelaySeconds);

                if (newStatus == OutboxEventStatus.RETRY) {
                    outboxEventRepository.finalizeAsRetry(event.getId(), workerId, nextAttempt, e.getMessage());
                } else {
                    outboxEventRepository.finalizeAsDeadLetter(event.getId(), workerId, e.getMessage());
                }
            }
        }
    }
}
