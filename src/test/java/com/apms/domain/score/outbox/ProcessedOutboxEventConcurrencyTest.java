package com.apms.domain.score.outbox;

import com.apms.domain.score.service.RoleEvaluationSqlIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.jdbc.JdbcTestUtils;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ProcessedOutboxEventConcurrencyTest extends RoleEvaluationSqlIntegrationTestBase {

    @Autowired
    private SqlIdempotencyService sqlIdempotencyService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        JdbcTestUtils.deleteFromTables(jdbcTemplate, "processed_outbox_events");
    }

    @Test
    void testConcurrentAcquireReceipt() throws Exception {
        assertTrue(AopUtils.isAopProxy(sqlIdempotencyService), "SqlIdempotencyService must be an AOP proxy");

        ExecutorService executorService = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        String eventId = UUID.randomUUID().toString();
        String eventType = "PARTNER_EVALUATION_APPROVED";
        String aggregateId = "evaluation-test-001";
        String payloadHash = "payload-hash-test-001";

        Callable<SqlIdempotencyService.ClaimResult> task = () -> {
            readyLatch.countDown();
            startLatch.await();
            return sqlIdempotencyService.acquireReceipt(eventId, eventType, aggregateId, payloadHash);
        };

        Future<SqlIdempotencyService.ClaimResult> future1 = null;
        Future<SqlIdempotencyService.ClaimResult> future2 = null;

        try {
            future1 = executorService.submit(task);
            future2 = executorService.submit(task);

            readyLatch.await(5, TimeUnit.SECONDS);
            startLatch.countDown();

            SqlIdempotencyService.ClaimResult result1 = future1.get(10, TimeUnit.SECONDS);
            SqlIdempotencyService.ClaimResult result2 = future2.get(10, TimeUnit.SECONDS);

            int acquiredCount = 0;
            int alreadyProcessedCount = 0;

            if (result1 == SqlIdempotencyService.ClaimResult.ACQUIRED) acquiredCount++;
            else if (result1 == SqlIdempotencyService.ClaimResult.ALREADY_PROCESSED) alreadyProcessedCount++;

            if (result2 == SqlIdempotencyService.ClaimResult.ACQUIRED) acquiredCount++;
            else if (result2 == SqlIdempotencyService.ClaimResult.ALREADY_PROCESSED) alreadyProcessedCount++;

            assertEquals(1, acquiredCount, "Exactly one thread should acquire the receipt");
            assertEquals(1, alreadyProcessedCount, "Exactly one thread should fail to acquire");

            int rowCount = JdbcTestUtils.countRowsInTable(jdbcTemplate, "processed_outbox_events");
            assertEquals(1, rowCount, "Exactly one row should exist in the table");

            Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM processed_outbox_events");
            assertEquals(eventId, row.get("event_id"));
            assertEquals(eventType, row.get("event_type"));
            assertEquals(aggregateId, row.get("aggregate_id"));
            assertEquals(payloadHash, row.get("payload_hash"));
            assertNotNull(row.get("processed_at"));
        } finally {
            executorService.shutdown();
        }
    }
}
