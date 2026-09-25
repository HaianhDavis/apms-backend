-- ====================================================================
-- Migration: V17__Add_Notification_Entity_Fields.sql
-- Description: Add company_profile_id, entity_id, and entity_type columns
--              to notifications table to support Company Profile and
--              Relationship Closeness lifecycle notifications.
-- ====================================================================

IF NOT EXISTS (
    SELECT 1 FROM sys.columns
    WHERE object_id = OBJECT_ID('notifications')
      AND name = 'company_profile_id'
)
BEGIN
    ALTER TABLE notifications ADD company_profile_id VARCHAR(100) NULL;
END;

IF NOT EXISTS (
    SELECT 1 FROM sys.columns
    WHERE object_id = OBJECT_ID('notifications')
      AND name = 'entity_id'
)
BEGIN
    ALTER TABLE notifications ADD entity_id VARCHAR(100) NULL;
END;

IF NOT EXISTS (
    SELECT 1 FROM sys.columns
    WHERE object_id = OBJECT_ID('notifications')
      AND name = 'entity_type'
)
BEGIN
    ALTER TABLE notifications ADD entity_type VARCHAR(50) NULL;
END;

-- Non-clustered index to speed up lifecycle notification deduplication checks
IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
    WHERE object_id = OBJECT_ID('notifications')
      AND name = 'IX_notifications_lifecycle_dedup'
)
BEGIN
    CREATE NONCLUSTERED INDEX IX_notifications_lifecycle_dedup
    ON notifications (recipient_account_id, action_type, entity_id, is_deleted)
    INCLUDE (company_profile_id);
END;
