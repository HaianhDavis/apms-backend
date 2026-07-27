package com.apms.domain.score.outbox;

import com.apms.common.enums.OutboxEventStatus;
import com.apms.domain.score.enums.RoleEvaluationOutboxEventType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "role_evaluation_outbox_events")
@CompoundIndexes({
    @CompoundIndex(name = "status_nextAttempt_locked_idx", def = "{'status': 1, 'nextAttemptAt': 1, 'lockedAt': 1}"),
    @CompoundIndex(name = "evaluation_event_idx", def = "{'evaluationId': 1, 'eventType': 1}")
})
public class RoleEvaluationOutboxEvent {
    @Id
    private String id;

    @Indexed(unique = true)
    private String eventId;

    private String evaluationId;
    private Long projectId;
    private Long taskId;

    private RoleEvaluationOutboxEventType eventType;

    private OutboxEventStatus status;

    private String payloadHash;

    private RoleEvaluationOutboxPayload payload;

    private LocalDateTime lockedAt;
    private String lockedBy;

    private Integer attemptCount;
    private LocalDateTime nextAttemptAt;

    private String lastError;

    private LocalDateTime createdAt;
    private LocalDateTime processedAt;

    @org.springframework.data.annotation.Version
    private Long version;
}
