-- ====================================================================
-- Migration: V18__Alter_Notifications_Title_To_Nvarchar.sql
-- Description: Convert notifications.title and ensure all user-visible
--              text columns use NVARCHAR for full Vietnamese Unicode
--              character preservation.
-- ====================================================================

-- 1. Ensure title is NVARCHAR(255) NOT NULL
IF EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'notifications'
      AND COLUMN_NAME = 'title'
      AND DATA_TYPE = 'varchar'
)
BEGIN
    ALTER TABLE notifications ALTER COLUMN title NVARCHAR(255) NOT NULL;
END;

-- 2. Ensure message is NVARCHAR(MAX) NULL
IF EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'notifications'
      AND COLUMN_NAME = 'message'
      AND DATA_TYPE = 'varchar'
)
BEGIN
    ALTER TABLE notifications ALTER COLUMN message NVARCHAR(MAX) NULL;
END;

-- 3. Ensure reject_reason is NVARCHAR(MAX) NULL
IF EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'notifications'
      AND COLUMN_NAME = 'reject_reason'
      AND DATA_TYPE = 'varchar'
)
BEGIN
    ALTER TABLE notifications ALTER COLUMN reject_reason NVARCHAR(MAX) NULL;
END;
