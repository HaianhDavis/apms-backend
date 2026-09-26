-- =====================================================================================
-- Migration: V24__Make_Company_Monitoring_Schedule_Columns_Nullable.sql
-- Description: Make schedule-related columns in company_monitoring_assignments nullable
--              to support the simplified assignment-only monitoring workflow (removal
--              of recurring review cycle, schedule deadlines, and next review dates).
--
-- Target Columns:
--   1. next_review_at: DATETIME2 NULL (was NOT NULL in previous recurring workflow)
--   2. frequency:      VARCHAR(255) NULL (was NOT NULL in previous recurring workflow)
--
-- Safety & Idempotency:
--   - Uses INFORMATION_SCHEMA.COLUMNS to alter only if currently NOT NULL.
--   - Preserves exact existing data types (DATETIME2, VARCHAR(255)).
--   - Historical data is preserved without data loss.
-- =====================================================================================

-- 1. Alter next_review_at from DATETIME2 NOT NULL to DATETIME2 NULL
IF EXISTS (
    SELECT 1 
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'company_monitoring_assignments'
      AND COLUMN_NAME = 'next_review_at'
      AND IS_NULLABLE = 'NO'
)
BEGIN
    ALTER TABLE company_monitoring_assignments
    ALTER COLUMN next_review_at DATETIME2 NULL;
    PRINT 'Updated company_monitoring_assignments.next_review_at to DATETIME2 NULL';
END
ELSE
BEGIN
    PRINT 'company_monitoring_assignments.next_review_at is already NULL or table does not exist.';
END;

-- 2. Alter frequency from VARCHAR(255) NOT NULL to VARCHAR(255) NULL
IF EXISTS (
    SELECT 1 
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'company_monitoring_assignments'
      AND COLUMN_NAME = 'frequency'
      AND IS_NULLABLE = 'NO'
)
BEGIN
    ALTER TABLE company_monitoring_assignments
    ALTER COLUMN frequency VARCHAR(255) NULL;
    PRINT 'Updated company_monitoring_assignments.frequency to VARCHAR(255) NULL';
END
ELSE
BEGIN
    PRINT 'company_monitoring_assignments.frequency is already NULL or table does not exist.';
END;

-- 3. Verify review_start_at and review_deadline_at if present
IF EXISTS (
    SELECT 1 
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'company_monitoring_assignments'
      AND COLUMN_NAME = 'review_start_at'
      AND IS_NULLABLE = 'NO'
)
BEGIN
    ALTER TABLE company_monitoring_assignments
    ALTER COLUMN review_start_at DATETIME2 NULL;
    PRINT 'Updated company_monitoring_assignments.review_start_at to DATETIME2 NULL';
END;

IF EXISTS (
    SELECT 1 
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'company_monitoring_assignments'
      AND COLUMN_NAME = 'review_deadline_at'
      AND IS_NULLABLE = 'NO'
)
BEGIN
    ALTER TABLE company_monitoring_assignments
    ALTER COLUMN review_deadline_at DATETIME2 NULL;
    PRINT 'Updated company_monitoring_assignments.review_deadline_at to DATETIME2 NULL';
END;
