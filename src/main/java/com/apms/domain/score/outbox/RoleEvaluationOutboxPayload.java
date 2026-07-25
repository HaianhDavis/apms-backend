package com.apms.domain.score.outbox;

import com.apms.domain.score.enums.EvaluationCompletenessStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoleEvaluationOutboxPayload {
    private String eventId;
    private String eventType;
    private String evaluationId;
    private Long projectId;
    private Long taskId;
    private Long submissionId;
    private String targetCompanyProfileId;
    private Long actorAccountId;
    private Integer submittedRevisionNumber;
    private String submittedSourceSnapshotHash;
    private String approvedVersionId;
    private Integer approvedVersionNumber;
    private String managerFeedback;
    private String managerJustification;
    private EvaluationCompletenessStatus aggregateCompletenessStatus;
    private LocalDateTime occurredAt;
    private Integer payloadVersion;
}
