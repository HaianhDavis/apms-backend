package com.apms.domain.score.outbox;

import com.apms.domain.score.enums.RoleEvaluationOutboxEventType;

public interface RoleEvaluationOutboxEventProcessorStrategy {
    boolean supports(RoleEvaluationOutboxEventType eventType);

    void process(RoleEvaluationOutboxEvent event);
}
