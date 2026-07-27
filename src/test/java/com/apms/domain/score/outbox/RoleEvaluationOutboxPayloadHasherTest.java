package com.apms.domain.score.outbox;

import com.apms.domain.score.enums.EvaluationCompletenessStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleEvaluationOutboxPayloadHasherTest {

    @Test
    void hash_identicalPayloads_produceIdenticalHashes() {
        LocalDateTime now = LocalDateTime.now();
        RoleEvaluationOutboxPayload payload1 = RoleEvaluationOutboxPayload.builder()
                .eventId("evt-123")
                .eventType("SUBMITTED")
                .evaluationId("eval-456")
                .projectId(10L)
                .taskId(20L)
                .aggregateCompletenessStatus(EvaluationCompletenessStatus.COMPLETE)
                .occurredAt(now)
                .build();

        RoleEvaluationOutboxPayload payload2 = RoleEvaluationOutboxPayload.builder()
                .eventId("evt-123")
                .eventType("SUBMITTED")
                .evaluationId("eval-456")
                .projectId(10L)
                .taskId(20L)
                .aggregateCompletenessStatus(EvaluationCompletenessStatus.COMPLETE)
                .occurredAt(now)
                .build();

        String hash1 = RoleEvaluationOutboxPayloadHasher.hash(payload1);
        String hash2 = RoleEvaluationOutboxPayloadHasher.hash(payload2);

        assertNotNull(hash1);
        assertTrue(hash1.startsWith("v1:sha256:"));
        assertEquals(hash1, hash2);
    }

    @Test
    void hash_changedField_producesDifferentHash() {
        LocalDateTime now = LocalDateTime.now();
        RoleEvaluationOutboxPayload payload1 = RoleEvaluationOutboxPayload.builder()
                .eventId("evt-123")
                .eventType("SUBMITTED")
                .evaluationId("eval-456")
                .projectId(10L)
                .occurredAt(now)
                .build();

        RoleEvaluationOutboxPayload payload2 = RoleEvaluationOutboxPayload.builder()
                .eventId("evt-123")
                .eventType("SUBMITTED")
                .evaluationId("eval-456")
                .projectId(11L) // changed
                .occurredAt(now)
                .build();

        String hash1 = RoleEvaluationOutboxPayloadHasher.hash(payload1);
        String hash2 = RoleEvaluationOutboxPayloadHasher.hash(payload2);

        assertNotEquals(hash1, hash2);
    }

    @Test
    void hash_goldenVectorTest() {
        // 4. Add a golden-vector test with one fixed payload and fixed expected hash.
        LocalDateTime fixedTime = LocalDateTime.of(2026, 7, 25, 8, 0, 0);
        RoleEvaluationOutboxPayload payload = RoleEvaluationOutboxPayload.builder()
                .eventId("fixed-event-id")
                .eventType("SUBMITTED")
                .evaluationId("fixed-evaluation-id")
                .projectId(999L)
                .taskId(888L)
                .submissionId(777L)
                .targetCompanyProfileId("fixed-company-id")
                .actorAccountId(123L)
                .submittedRevisionNumber(2)
                .submittedSourceSnapshotHash("fixed-source-hash")
                .approvedVersionId("fixed-version-id")
                .approvedVersionNumber(3)
                .managerFeedback("good job")
                .managerJustification("approved because yes")
                .aggregateCompletenessStatus(EvaluationCompletenessStatus.COMPLETE)
                .occurredAt(fixedTime)
                .payloadVersion(1)
                .build();

        String hash = RoleEvaluationOutboxPayloadHasher.hash(payload);

        // This is a placeholder expected hash. We will print the actual one if it fails and fix it,
        // but since we want the test to pass eventually, we need the exact hash.
        // Let's assert it starts with the prefix for now, and I will run the test to get the real hash.
        assertNotNull(hash);
        assertEquals("v1:sha256:e8d2b0a9cd1644c899962456825ea26b68ec72e2715edf1842bd36d293e7aafe", hash);
    }
}
