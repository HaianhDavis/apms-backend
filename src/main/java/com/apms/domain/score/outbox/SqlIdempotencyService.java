package com.apms.domain.score.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class SqlIdempotencyService {

    private final JdbcTemplate jdbcTemplate;

    public SqlIdempotencyService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public enum ClaimResult {
        ACQUIRED,
        ALREADY_PROCESSED
    }

    /**
     * Executes atomic receipt acquisition using an INSERT ... SELECT WHERE NOT EXISTS.
     * This avoids generic DataIntegrityViolationExceptions and ensures precise SQL concurrency using UPDLOCK/HOLDLOCK.
     */
    @Transactional(transactionManager = "transactionManager")
    public ClaimResult acquireReceipt(String eventId, String eventType, String aggregateId, String payloadHash) {
        String sql = "INSERT INTO processed_outbox_events (event_id, event_type, aggregate_id, payload_hash, processed_at) " +
                "SELECT ?, ?, ?, ?, ? " +
                "WHERE NOT EXISTS ( " +
                "    SELECT 1 FROM processed_outbox_events WITH (UPDLOCK, HOLDLOCK) " +
                "    WHERE event_id = ? " +
                ")";

        int rowsAffected = jdbcTemplate.update(sql,
                eventId, eventType, aggregateId, payloadHash, LocalDateTime.now(),
                eventId);

        return rowsAffected > 0 ? ClaimResult.ACQUIRED : ClaimResult.ALREADY_PROCESSED;
    }
}
