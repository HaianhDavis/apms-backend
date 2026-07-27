package com.apms.domain.score.outbox;

import com.apms.common.enums.OutboxEventStatus;
import com.apms.domain.score.repository.mongo.RoleEvaluationOutboxEventRepository;
import com.apms.domain.score.service.RoleEvaluationMongoIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class RoleEvaluationOutboxClaimIntegrationTest extends RoleEvaluationMongoIntegrationTestBase {

    @Autowired
    private RoleEvaluationOutboxEventRepository outboxEventRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
    }

    private RoleEvaluationOutboxEvent createEvent(OutboxEventStatus status, LocalDateTime retryAt, String lockedBy, LocalDateTime lockedAt) {
        RoleEvaluationOutboxEvent event = new RoleEvaluationOutboxEvent();
        event.setId(UUID.randomUUID().toString());
        event.setEventId(UUID.randomUUID().toString());
        event.setStatus(status);
        event.setCreatedAt(LocalDateTime.now().minusDays(1));
        event.setNextAttemptAt(retryAt);
        event.setLockedBy(lockedBy);
        event.setLockedAt(lockedAt);
        event.setAttemptCount(0);
        return outboxEventRepository.save(event);
    }

    @Test
    void testTwoWorkersClaimConcurrentlyOnlyOneSucceeds() throws InterruptedException {
        RoleEvaluationOutboxEvent event = createEvent(OutboxEventStatus.PENDING, null, null, null);

        int numWorkers = 2;
        ExecutorService executor = Executors.newFixedThreadPool(numWorkers);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(numWorkers);
        AtomicInteger successCount = new AtomicInteger(0);

        String[] workerIds = {"worker-A", "worker-B"};
        String[] successfulWorkerId = new String[1];

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime leaseUntil = now.plusMinutes(5);

        for (int i = 0; i < numWorkers; i++) {
            final String workerId = workerIds[i];
            executor.submit(() -> {
                try {
                    latch.await();
                    Optional<RoleEvaluationOutboxEvent> claimed = outboxEventRepository.claimNextEvent(workerId, now, leaseUntil);
                    if (claimed.isPresent()) {
                        successCount.incrementAndGet();
                        successfulWorkerId[0] = workerId;
                    }
                } catch (Exception e) {
                    // Ignore
                } finally {
                    done.countDown();
                }
            });
        }

        latch.countDown(); // Start all workers
        done.await(); // Wait for all to finish

        assertEquals(1, successCount.get(), "Exactly one worker should succeed in claiming the event");

        RoleEvaluationOutboxEvent reloaded = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertEquals(successfulWorkerId[0], reloaded.getLockedBy());
        assertEquals(OutboxEventStatus.PROCESSING, reloaded.getStatus());
        assertEquals(1, reloaded.getAttemptCount());
    }

    @Test
    void testDuePendingEventIsClaimed() {
        createEvent(OutboxEventStatus.PENDING, null, null, null);

        Optional<RoleEvaluationOutboxEvent> claimed = outboxEventRepository.claimNextEvent("worker", LocalDateTime.now(), LocalDateTime.now().plusMinutes(5));
        assertTrue(claimed.isPresent());
        assertEquals(OutboxEventStatus.PROCESSING, claimed.get().getStatus());
        assertEquals("worker", claimed.get().getLockedBy());
        assertEquals(1, claimed.get().getAttemptCount(), "Attempt count should increment during claim");
    }

    @Test
    void testDueRetryEventIsClaimed() {
        createEvent(OutboxEventStatus.RETRY, LocalDateTime.now().minusMinutes(1), null, null);

        Optional<RoleEvaluationOutboxEvent> claimed = outboxEventRepository.claimNextEvent("worker", LocalDateTime.now(), LocalDateTime.now().plusMinutes(5));
        assertTrue(claimed.isPresent());
        assertEquals(OutboxEventStatus.PROCESSING, claimed.get().getStatus());
    }

    @Test
    void testFutureRetryEventIsNotClaimed() {
        createEvent(OutboxEventStatus.RETRY, LocalDateTime.now().plusMinutes(10), null, null);

        Optional<RoleEvaluationOutboxEvent> claimed = outboxEventRepository.claimNextEvent("worker", LocalDateTime.now(), LocalDateTime.now().plusMinutes(5));
        assertFalse(claimed.isPresent());
    }

    @Test
    void testActiveProcessingLockIsNotReclaimed() {
        createEvent(OutboxEventStatus.PROCESSING, null, "active-worker", LocalDateTime.now().plusMinutes(5)); // Lock expires in future

        Optional<RoleEvaluationOutboxEvent> claimed = outboxEventRepository.claimNextEvent("new-worker", LocalDateTime.now(), LocalDateTime.now().plusMinutes(5));
        assertFalse(claimed.isPresent());
    }

    @Test
    void testExpiredProcessingLockIsReclaimed() {
        createEvent(OutboxEventStatus.PROCESSING, null, "crashed-worker", LocalDateTime.now().minusMinutes(5)); // Lock expired 5 mins ago

        Optional<RoleEvaluationOutboxEvent> claimed = outboxEventRepository.claimNextEvent("new-worker", LocalDateTime.now(), LocalDateTime.now().plusMinutes(5));
        assertTrue(claimed.isPresent());
        assertEquals("new-worker", claimed.get().getLockedBy());
    }

    @Test
    void testProcessedAndDeadLetterAreNotReclaimed() {
        createEvent(OutboxEventStatus.PROCESSED, null, null, null);
        createEvent(OutboxEventStatus.DEAD_LETTER, null, null, null);

        Optional<RoleEvaluationOutboxEvent> claimed = outboxEventRepository.claimNextEvent("worker", LocalDateTime.now(), LocalDateTime.now().plusMinutes(5));
        assertFalse(claimed.isPresent());
    }

    @Test
    void testStaleWorkerCannotMarkAnotherWorkersEventProcessed() {
        RoleEvaluationOutboxEvent event = createEvent(OutboxEventStatus.PENDING, null, null, null);

        // Worker 1 claims it
        Optional<RoleEvaluationOutboxEvent> claimedByWorker1 = outboxEventRepository.claimNextEvent("worker1", LocalDateTime.now(), LocalDateTime.now().plusMinutes(5));
        assertTrue(claimedByWorker1.isPresent());

        // Simulating lock expiration and Worker 2 reclaims it
        RoleEvaluationOutboxEvent reclaimed = outboxEventRepository.findById(claimedByWorker1.get().getId()).orElseThrow();
        reclaimed.setLockedAt(LocalDateTime.now().minusMinutes(10));
        outboxEventRepository.save(reclaimed);

        Optional<RoleEvaluationOutboxEvent> claimedByWorker2 = outboxEventRepository.claimNextEvent("worker2", LocalDateTime.now(), LocalDateTime.now().plusMinutes(5));
        assertTrue(claimedByWorker2.isPresent());
        assertEquals("worker2", claimedByWorker2.get().getLockedBy());

        // Worker 1 tries to finalize it
        assertThrows(OptimisticLockingFailureException.class, () -> outboxEventRepository.finalizeAsProcessed(event.getId(), "worker1"));
    }

    @Test
    void testStaleWorkerCannotMarkAnotherWorkersEventRetry() {
        RoleEvaluationOutboxEvent event = createEvent(OutboxEventStatus.PENDING, null, null, null);

        Optional<RoleEvaluationOutboxEvent> claimedByWorker1 = outboxEventRepository.claimNextEvent("worker1", LocalDateTime.now(), LocalDateTime.now().plusMinutes(5));

        RoleEvaluationOutboxEvent reclaimed = outboxEventRepository.findById(claimedByWorker1.get().getId()).orElseThrow();
        reclaimed.setLockedAt(LocalDateTime.now().minusMinutes(10));
        outboxEventRepository.save(reclaimed);

        outboxEventRepository.claimNextEvent("worker2", LocalDateTime.now(), LocalDateTime.now().plusMinutes(5));

        assertThrows(OptimisticLockingFailureException.class, () -> outboxEventRepository.finalizeAsRetry(event.getId(), "worker1", LocalDateTime.now(), "error"));
    }

    @Test
    void testStaleWorkerCannotMarkAnotherWorkersEventDeadLetter() {
        RoleEvaluationOutboxEvent event = createEvent(OutboxEventStatus.PENDING, null, null, null);

        Optional<RoleEvaluationOutboxEvent> claimedByWorker1 = outboxEventRepository.claimNextEvent("worker1", LocalDateTime.now(), LocalDateTime.now().plusMinutes(5));

        RoleEvaluationOutboxEvent reclaimed = outboxEventRepository.findById(claimedByWorker1.get().getId()).orElseThrow();
        reclaimed.setLockedAt(LocalDateTime.now().minusMinutes(10));
        outboxEventRepository.save(reclaimed);

        outboxEventRepository.claimNextEvent("worker2", LocalDateTime.now(), LocalDateTime.now().plusMinutes(5));

        assertThrows(OptimisticLockingFailureException.class, () -> outboxEventRepository.finalizeAsDeadLetter(event.getId(), "worker1", "error"));
    }
}
