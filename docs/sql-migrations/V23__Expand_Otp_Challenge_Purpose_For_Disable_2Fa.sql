-- =====================================================================================
-- Migration: V23__Expand_Otp_Challenge_Purpose_For_Disable_2Fa.sql
-- Description: Expand CHECK constraint on dbo.otp_challenges.purpose to allow DISABLE_2FA.
--
-- Safety & Idempotency:
-- - Inspects sys.check_constraints on dbo.otp_challenges.
-- - If the constraint already contains 'DISABLE_2FA', no action is taken.
-- - If 'DISABLE_2FA' is missing, drops the existing constraint and recreates
--   CK_otp_challenges_purpose with all existing values plus DISABLE_2FA.
-- =====================================================================================

IF EXISTS (
    SELECT 1
    FROM sys.check_constraints cc
    WHERE cc.parent_object_id = OBJECT_ID('dbo.otp_challenges')
      AND (cc.name = 'CK_otp_challenges_purpose' OR cc.definition LIKE '%purpose%')
      AND cc.definition LIKE '%DISABLE_2FA%'
)
BEGIN
    PRINT 'CHECK constraint on dbo.otp_challenges.purpose already includes DISABLE_2FA. No changes needed.';
END
ELSE
BEGIN
    DECLARE @ConstraintName NVARCHAR(200);

    -- Find any CHECK constraint bound to purpose column on dbo.otp_challenges
    SELECT @ConstraintName = cc.name
    FROM sys.check_constraints cc
    JOIN sys.columns col ON cc.parent_object_id = col.object_id AND cc.parent_column_id = col.column_id
    WHERE cc.parent_object_id = OBJECT_ID('dbo.otp_challenges')
      AND col.name = 'purpose';

    IF @ConstraintName IS NULL
    BEGIN
        SELECT TOP 1 @ConstraintName = cc.name
        FROM sys.check_constraints cc
        WHERE cc.parent_object_id = OBJECT_ID('dbo.otp_challenges')
          AND cc.definition LIKE '%purpose%';
    END;

    IF @ConstraintName IS NOT NULL
    BEGIN
        DECLARE @DropSql NVARCHAR(1000) = 'ALTER TABLE dbo.otp_challenges DROP CONSTRAINT ' + QUOTENAME(@ConstraintName) + ';';
        EXEC sp_executesql @DropSql;
        PRINT 'Dropped existing constraint ' + @ConstraintName;
    END;

    ALTER TABLE dbo.otp_challenges
    ADD CONSTRAINT CK_otp_challenges_purpose CHECK (
        [purpose] = 'CONFIDENTIAL_COMPANY_NEWS' OR
        [purpose] = 'EMAIL_VERIFICATION' OR
        [purpose] = 'LOGIN_MFA' OR
        [purpose] = 'LOGIN_MFA_ENROLLMENT' OR
        [purpose] = 'DISABLE_2FA'
    );
    PRINT 'Created constraint CK_otp_challenges_purpose including DISABLE_2FA.';
END;
GO
