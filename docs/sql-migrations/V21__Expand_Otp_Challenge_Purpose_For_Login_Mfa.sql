-- V21__Expand_Otp_Challenge_Purpose_For_Login_Mfa.sql
-- Expand CHECK constraint on dbo.otp_challenges.purpose to support LOGIN_MFA and LOGIN_MFA_ENROLLMENT

DECLARE @ConstraintName nvarchar(200);

-- Locate any CHECK constraint on dbo.otp_challenges bound to column 'purpose'
SELECT @ConstraintName = cc.name
FROM sys.check_constraints cc
JOIN sys.columns col ON cc.parent_object_id = col.object_id AND cc.parent_column_id = col.column_id
WHERE cc.parent_object_id = OBJECT_ID('dbo.otp_challenges')
  AND col.name = 'purpose';

-- Fallback if the constraint wasn't bound directly to parent_column_id
IF @ConstraintName IS NULL
BEGIN
    SELECT TOP 1 @ConstraintName = cc.name
    FROM sys.check_constraints cc
    WHERE cc.parent_object_id = OBJECT_ID('dbo.otp_challenges')
      AND cc.definition LIKE '%purpose%';
END

IF @ConstraintName IS NOT NULL
BEGIN
    DECLARE @SQL nvarchar(1000) = 'ALTER TABLE dbo.otp_challenges DROP CONSTRAINT ' + QUOTENAME(@ConstraintName) + ';';
    EXEC sp_executesql @SQL;
END
GO

-- Recreate constraint with deterministic name CK_otp_challenges_purpose
IF NOT EXISTS (
    SELECT 1 FROM sys.check_constraints
    WHERE parent_object_id = OBJECT_ID('dbo.otp_challenges')
      AND name = 'CK_otp_challenges_purpose'
)
BEGIN
    ALTER TABLE dbo.otp_challenges
    ADD CONSTRAINT CK_otp_challenges_purpose CHECK (
        [purpose] = 'CONFIDENTIAL_COMPANY_NEWS' OR
        [purpose] = 'EMAIL_VERIFICATION' OR
        [purpose] = 'LOGIN_MFA' OR
        [purpose] = 'LOGIN_MFA_ENROLLMENT'
    );
END
GO
