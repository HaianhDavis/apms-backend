package com.apms.domain.score.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoleEvaluationOutboxEventProcessorDelegator {

    private final SqlIdempotencyService idempotencyService;

    @Transactional(transactionManager = "transactionManager")
    public void processEventWithIdempotency(RoleEvaluationOutboxEvent event, RoleEvaluationOutboxEventProcessorStrategy strategy) {

        String aggregateId = event.getEvaluationId();
        String payloadHash = null; // Can be added later if needed for advanced idempotency matching

        SqlIdempotencyService.ClaimResult result = idempotencyService.acquireReceipt(
                event.getEventId(),
                event.getEventType().name(),
                aggregateId,
                payloadHash);

        if (result == SqlIdempotencyService.ClaimResult.ACQUIRED) {
            log.info("Event {} acquired, executing SQL transition.", event.getEventId());
            strategy.process(event);
        } else {
            log.info("Event {} already processed, skipping SQL transition.", event.getEventId());
        }
    }
}
