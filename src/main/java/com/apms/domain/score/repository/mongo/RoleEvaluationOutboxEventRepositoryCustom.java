package com.apms.domain.score.repository.mongo;

import com.apms.domain.score.outbox.RoleEvaluationOutboxEvent;
import java.time.LocalDateTime;
import java.util.Optional;

public interface RoleEvaluationOutboxEventRepositoryCustom {
    Optional<RoleEvaluationOutboxEvent> claimNextEvent(String workerId, LocalDateTime currentTime, LocalDateTime leaseUntil);
    void finalizeAsProcessed(String eventId, String workerId);
    void finalizeAsRetry(String eventId, String workerId, LocalDateTime nextAttempt, String lastError);
    void finalizeAsDeadLetter(String eventId, String workerId, String lastError);
}
