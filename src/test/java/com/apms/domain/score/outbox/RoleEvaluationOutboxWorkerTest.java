package com.apms.domain.score.outbox;

import com.apms.common.enums.OutboxEventStatus;
import com.apms.domain.score.enums.RoleEvaluationOutboxEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.dao.OptimisticLockingFailureException;

import com.apms.domain.score.repository.mongo.RoleEvaluationOutboxEventRepository;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class RoleEvaluationOutboxWorkerTest {

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private RoleEvaluationOutboxEventRepository outboxEventRepository;

    @Mock
    private RoleEvaluationOutboxEventProcessorDelegator delegator;

    @Mock
    private RoleEvaluationOutboxEventProcessorStrategy strategy;

    private RoleEvaluationOutboxEventWorker worker;
    private RoleEvaluationOutboxProperties properties;
    private String workerId;

    @BeforeEach
    void setUp() {
        properties = new RoleEvaluationOutboxProperties();
        properties.setBatchSize(1); // Default to 1
        worker = new RoleEvaluationOutboxEventWorker(mongoTemplate, outboxEventRepository, delegator, List.of(strategy), properties);
        workerId = (String) ReflectionTestUtils.getField(worker, "workerId");
    }

    @Test
    void batchSizeOneProcessesAtMostOneEvent() {
        RoleEvaluationOutboxEvent event1 = new RoleEvaluationOutboxEvent();
        event1.setId("event-1");
        event1.setEventType(RoleEvaluationOutboxEventType.PARTNER_EVALUATION_SUBMITTED);
        event1.setPayload(RoleEvaluationOutboxPayload.builder().eventId("event-1").build());
        event1.setPayloadHash(RoleEvaluationOutboxPayloadHasher.hash(event1.getPayload()));

        properties.setBatchSize(1);

        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(RoleEvaluationOutboxEvent.class)))
                .thenReturn(event1)
                .thenReturn(new RoleEvaluationOutboxEvent()); // Should not be called a second time
        when(strategy.supports(any())).thenReturn(true);

        worker.processEvents();

        verify(mongoTemplate, times(1)).findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(RoleEvaluationOutboxEvent.class));
        verify(delegator, times(1)).processEventWithIdempotency(any(), any());
        verify(outboxEventRepository, times(1)).finalizeAsProcessed("event-1", workerId);
    }

    @Test
    void batchSizeThreeProcessesAtMostThreeEvents() {
        RoleEvaluationOutboxEvent e1 = new RoleEvaluationOutboxEvent(); e1.setId("1"); e1.setEventType(RoleEvaluationOutboxEventType.PARTNER_EVALUATION_SUBMITTED); e1.setPayload(RoleEvaluationOutboxPayload.builder().eventId("1").build()); e1.setPayloadHash(RoleEvaluationOutboxPayloadHasher.hash(e1.getPayload()));
        RoleEvaluationOutboxEvent e2 = new RoleEvaluationOutboxEvent(); e2.setId("2"); e2.setEventType(RoleEvaluationOutboxEventType.PARTNER_EVALUATION_SUBMITTED); e2.setPayload(RoleEvaluationOutboxPayload.builder().eventId("2").build()); e2.setPayloadHash(RoleEvaluationOutboxPayloadHasher.hash(e2.getPayload()));
        RoleEvaluationOutboxEvent e3 = new RoleEvaluationOutboxEvent(); e3.setId("3"); e3.setEventType(RoleEvaluationOutboxEventType.PARTNER_EVALUATION_SUBMITTED); e3.setPayload(RoleEvaluationOutboxPayload.builder().eventId("3").build()); e3.setPayloadHash(RoleEvaluationOutboxPayloadHasher.hash(e3.getPayload()));
        RoleEvaluationOutboxEvent e4 = new RoleEvaluationOutboxEvent(); e4.setId("4"); e4.setEventType(RoleEvaluationOutboxEventType.PARTNER_EVALUATION_SUBMITTED); e4.setPayload(RoleEvaluationOutboxPayload.builder().eventId("4").build()); e4.setPayloadHash(RoleEvaluationOutboxPayloadHasher.hash(e4.getPayload()));

        properties.setBatchSize(3);

        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(RoleEvaluationOutboxEvent.class)))
                .thenReturn(e1)
                .thenReturn(e2)
                .thenReturn(e3)
                .thenReturn(e4); // 4th should not be claimed
        when(strategy.supports(any())).thenReturn(true);

        worker.processEvents();

        verify(mongoTemplate, times(3)).findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(RoleEvaluationOutboxEvent.class));
        verify(delegator, times(3)).processEventWithIdempotency(any(), any());
        verify(outboxEventRepository, times(1)).finalizeAsProcessed("1", workerId);
        verify(outboxEventRepository, times(1)).finalizeAsProcessed("2", workerId);
        verify(outboxEventRepository, times(1)).finalizeAsProcessed("3", workerId);
        verify(outboxEventRepository, never()).finalizeAsProcessed("4", workerId);
    }

    @Test
    void batchStopsWhenNoMoreEvents() {
        RoleEvaluationOutboxEvent e1 = new RoleEvaluationOutboxEvent(); e1.setId("1"); e1.setEventType(RoleEvaluationOutboxEventType.PARTNER_EVALUATION_SUBMITTED); e1.setPayload(RoleEvaluationOutboxPayload.builder().eventId("1").build()); e1.setPayloadHash(RoleEvaluationOutboxPayloadHasher.hash(e1.getPayload()));

        properties.setBatchSize(5);

        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(RoleEvaluationOutboxEvent.class)))
                .thenReturn(e1)
                .thenReturn(null);
        when(strategy.supports(any())).thenReturn(true);

        worker.processEvents();

        verify(mongoTemplate, times(2)).findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(RoleEvaluationOutboxEvent.class));
        verify(delegator, times(1)).processEventWithIdempotency(any(), any());
        verify(outboxEventRepository, times(1)).finalizeAsProcessed("1", workerId);
    }

    @Test
    void noClaimPerformsOneClaimAttempt() {
        properties.setBatchSize(3);

        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(RoleEvaluationOutboxEvent.class)))
                .thenReturn(null);

        worker.processEvents();

        verify(mongoTemplate, times(1)).findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(RoleEvaluationOutboxEvent.class));
        verify(delegator, never()).processEventWithIdempotency(any(), any());
        verify(outboxEventRepository, never()).finalizeAsProcessed(anyString(), anyString());
    }

    @Test
    void workerIdIsStableAcrossClaimAndFinalization() {
        RoleEvaluationOutboxEvent event = new RoleEvaluationOutboxEvent();
        event.setId("event-1");
        event.setEventType(RoleEvaluationOutboxEventType.PARTNER_EVALUATION_SUBMITTED); event.setPayload(RoleEvaluationOutboxPayload.builder().eventId("event-1").build()); event.setPayloadHash(RoleEvaluationOutboxPayloadHasher.hash(event.getPayload()));
        event.setPayload(RoleEvaluationOutboxPayload.builder().eventId("event-1").build());
        event.setPayloadHash(RoleEvaluationOutboxPayloadHasher.hash(event.getPayload()));

        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(RoleEvaluationOutboxEvent.class)))
                .thenReturn(event)
                .thenReturn(null);
        when(strategy.supports(any())).thenReturn(true);

        worker.processEvents();

        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).findAndModify(any(Query.class), updateCaptor.capture(), any(FindAndModifyOptions.class), eq(RoleEvaluationOutboxEvent.class));

        org.bson.Document updateDocument = updateCaptor.getValue().getUpdateObject();
        Object setObj = updateDocument.get("$set");
        assertNotNull(setObj, "\"$set\" exists");
        assertTrue(setObj instanceof org.bson.Document, "\"$set\" is a Document");

        org.bson.Document setDocument = (org.bson.Document) setObj;
        String lockedBy = setDocument.getString("lockedBy");

        assertNotNull(lockedBy);
        assertFalse(lockedBy.isBlank());
        assertEquals(workerId, lockedBy);

        verify(outboxEventRepository).finalizeAsProcessed(eq("event-1"), eq(lockedBy));
    }

    @Test
    void workerIdIsStableAcrossMultipleEvents() {
        RoleEvaluationOutboxEvent e1 = new RoleEvaluationOutboxEvent(); e1.setId("1"); e1.setEventType(RoleEvaluationOutboxEventType.PARTNER_EVALUATION_SUBMITTED); e1.setPayload(RoleEvaluationOutboxPayload.builder().eventId("1").build()); e1.setPayloadHash(RoleEvaluationOutboxPayloadHasher.hash(e1.getPayload()));
        RoleEvaluationOutboxEvent e2 = new RoleEvaluationOutboxEvent(); e2.setId("2"); e2.setEventType(RoleEvaluationOutboxEventType.PARTNER_EVALUATION_SUBMITTED); e2.setPayload(RoleEvaluationOutboxPayload.builder().eventId("2").build()); e2.setPayloadHash(RoleEvaluationOutboxPayloadHasher.hash(e2.getPayload()));

        properties.setBatchSize(2);
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(RoleEvaluationOutboxEvent.class)))
                .thenReturn(e1)
                .thenReturn(e2);
        when(strategy.supports(any())).thenReturn(true);

        worker.processEvents();

        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate, times(2)).findAndModify(any(Query.class), updateCaptor.capture(), any(FindAndModifyOptions.class), eq(RoleEvaluationOutboxEvent.class));

        List<Update> updates = updateCaptor.getAllValues();
        org.bson.Document doc1 = (org.bson.Document) updates.get(0).getUpdateObject().get("$set");
        org.bson.Document doc2 = (org.bson.Document) updates.get(1).getUpdateObject().get("$set");

        String lockedBy1 = doc1.getString("lockedBy");
        String lockedBy2 = doc2.getString("lockedBy");

        assertEquals(workerId, lockedBy1);
        assertEquals(workerId, lockedBy2);

        verify(outboxEventRepository).finalizeAsProcessed("1", workerId);
        verify(outboxEventRepository).finalizeAsProcessed("2", workerId);
    }

    // --- Previously verified retry/dead-letter and ownership-loss tests ---

    @Test
    void successfulProcessingFinalizesAsProcessed() {
        RoleEvaluationOutboxEvent event = new RoleEvaluationOutboxEvent();
        event.setId("event-1");
        event.setEventType(RoleEvaluationOutboxEventType.PARTNER_EVALUATION_SUBMITTED); event.setPayload(RoleEvaluationOutboxPayload.builder().eventId("event-1").build()); event.setPayloadHash(RoleEvaluationOutboxPayloadHasher.hash(event.getPayload()));

        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(RoleEvaluationOutboxEvent.class)))
                .thenReturn(event)
                .thenReturn(null);
        when(strategy.supports(any())).thenReturn(true);

        worker.processEvents();

        verify(delegator, times(1)).processEventWithIdempotency(event, strategy);
        verify(outboxEventRepository, times(1)).finalizeAsProcessed("event-1", workerId);
        verify(outboxEventRepository, never()).finalizeAsRetry(anyString(), anyString(), any(), anyString());
        verify(outboxEventRepository, never()).finalizeAsDeadLetter(anyString(), anyString(), anyString());
    }

    @Test
    void retryableFailureBelowMaxAttemptsSchedulesRetry() {
        RoleEvaluationOutboxEvent event = new RoleEvaluationOutboxEvent();
        event.setId("event-1");
        event.setEventType(RoleEvaluationOutboxEventType.PARTNER_EVALUATION_SUBMITTED); event.setPayload(RoleEvaluationOutboxPayload.builder().eventId("event-1").build()); event.setPayloadHash(RoleEvaluationOutboxPayloadHasher.hash(event.getPayload()));
        event.setAttemptCount(1);

        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(RoleEvaluationOutboxEvent.class)))
                .thenReturn(event)
                .thenReturn(null);
        when(strategy.supports(any())).thenReturn(true);
        doThrow(new RuntimeException("Transient failure")).when(delegator).processEventWithIdempotency(event, strategy);

        LocalDateTime beforeProcess = LocalDateTime.now();
        worker.processEvents();
        LocalDateTime afterProcess = LocalDateTime.now();

        ArgumentCaptor<LocalDateTime> timeCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(outboxEventRepository, times(1)).finalizeAsRetry(eq("event-1"), eq(workerId), timeCaptor.capture(), eq("Transient failure"));

        verify(outboxEventRepository, never()).finalizeAsProcessed(anyString(), anyString());
        verify(outboxEventRepository, never()).finalizeAsDeadLetter(anyString(), anyString(), anyString());

        LocalDateTime capturedTime = timeCaptor.getValue();
        long expectedDelaySeconds = 1;
        LocalDateTime expectedMin = beforeProcess.plusSeconds(expectedDelaySeconds);
        LocalDateTime expectedMax = afterProcess.plusSeconds(expectedDelaySeconds);
        assertTrue(!capturedTime.isBefore(expectedMin) && !capturedTime.isAfter(expectedMax));
    }

    @Test
    void failureAtMaxAttemptsMovesToDeadLetter() {
        RoleEvaluationOutboxEvent event = new RoleEvaluationOutboxEvent();
        event.setId("event-1");
        event.setEventType(RoleEvaluationOutboxEventType.PARTNER_EVALUATION_SUBMITTED); event.setPayload(RoleEvaluationOutboxPayload.builder().eventId("event-1").build()); event.setPayloadHash(RoleEvaluationOutboxPayloadHasher.hash(event.getPayload()));
        event.setPayload(RoleEvaluationOutboxPayload.builder().eventId("event-1").build());
        event.setPayloadHash(RoleEvaluationOutboxPayloadHasher.hash(event.getPayload()));
        event.setAttemptCount(5);

        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(RoleEvaluationOutboxEvent.class)))
                .thenReturn(event)
                .thenReturn(null);
        when(strategy.supports(any())).thenReturn(true);
        doThrow(new RuntimeException("Repeated failure")).when(delegator).processEventWithIdempotency(event, strategy);

        worker.processEvents();

        verify(outboxEventRepository, times(1)).finalizeAsDeadLetter("event-1", workerId, "Repeated failure");
        verify(outboxEventRepository, never()).finalizeAsRetry(anyString(), anyString(), any(), anyString());
        verify(outboxEventRepository, never()).finalizeAsProcessed(anyString(), anyString());
    }

    @Test
    void lostOwnershipAfterSuccessfulProcessingDoesNotRetry() {
        RoleEvaluationOutboxEvent event = new RoleEvaluationOutboxEvent();
        event.setId("event-1");
        event.setEventType(RoleEvaluationOutboxEventType.PARTNER_EVALUATION_SUBMITTED); event.setPayload(RoleEvaluationOutboxPayload.builder().eventId("event-1").build()); event.setPayloadHash(RoleEvaluationOutboxPayloadHasher.hash(event.getPayload()));

        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(RoleEvaluationOutboxEvent.class)))
                .thenReturn(event)
                .thenReturn(null);
        when(strategy.supports(any())).thenReturn(true);

        doThrow(new OptimisticLockingFailureException("Lost lock")).when(outboxEventRepository).finalizeAsProcessed("event-1", workerId);

        assertDoesNotThrow(() -> worker.processEvents());

        verify(outboxEventRepository, times(1)).finalizeAsProcessed("event-1", workerId);
        verify(outboxEventRepository, never()).finalizeAsRetry(anyString(), anyString(), any(), anyString());
        verify(outboxEventRepository, never()).finalizeAsDeadLetter(anyString(), anyString(), anyString());
    }

    @Test
    void lostOwnershipDuringRetryFinalizationDoesNotDeadLetter() {
        RoleEvaluationOutboxEvent event = new RoleEvaluationOutboxEvent();
        event.setId("event-1");
        event.setEventType(RoleEvaluationOutboxEventType.PARTNER_EVALUATION_SUBMITTED); event.setPayload(RoleEvaluationOutboxPayload.builder().eventId("event-1").build()); event.setPayloadHash(RoleEvaluationOutboxPayloadHasher.hash(event.getPayload()));
        event.setAttemptCount(1);

        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(RoleEvaluationOutboxEvent.class)))
                .thenReturn(event)
                .thenReturn(null);
        when(strategy.supports(any())).thenReturn(true);
        doThrow(new RuntimeException("Processing failed")).when(delegator).processEventWithIdempotency(event, strategy);

        doThrow(new OptimisticLockingFailureException("Lost lock during retry"))
            .when(outboxEventRepository).finalizeAsRetry(eq("event-1"), eq(workerId), any(), anyString());

        assertThrows(OptimisticLockingFailureException.class, () -> worker.processEvents());

        verify(outboxEventRepository, never()).finalizeAsDeadLetter(anyString(), anyString(), anyString());
    }

    @Test
    void batchSizeValidationWorks() {
        jakarta.validation.Validator validator = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();

        RoleEvaluationOutboxProperties props = new RoleEvaluationOutboxProperties();

        props.setBatchSize(0);
        var violations0 = validator.validate(props);
        assertFalse(violations0.isEmpty(), "batchSize = 0 should violate @Min(1)");
        assertTrue(violations0.stream().anyMatch(v -> v.getPropertyPath().toString().equals("batchSize")));

        props.setBatchSize(1);
        var violations1 = validator.validate(props);
        assertTrue(violations1.stream().noneMatch(v -> v.getPropertyPath().toString().equals("batchSize")), "batchSize = 1 should be valid");

        props.setBatchSize(3);
        var violations3 = validator.validate(props);
        assertTrue(violations3.stream().noneMatch(v -> v.getPropertyPath().toString().equals("batchSize")), "batchSize = 3 should be valid");
    }
}
