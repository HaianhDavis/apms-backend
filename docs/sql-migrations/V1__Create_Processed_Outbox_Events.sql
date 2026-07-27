IF OBJECT_ID('processed_outbox_events', 'U') IS NULL
BEGIN
    CREATE TABLE processed_outbox_events (
        event_id VARCHAR(255) PRIMARY KEY,
        event_type VARCHAR(255) NOT NULL,
        aggregate_id VARCHAR(255),
        payload_hash VARCHAR(255),
        processed_at DATETIME2 NOT NULL DEFAULT GETDATE()
    )
END;
