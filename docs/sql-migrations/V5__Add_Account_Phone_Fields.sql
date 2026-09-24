IF COL_LENGTH('accounts', 'phone_number') IS NULL
BEGIN
    ALTER TABLE accounts ADD phone_number NVARCHAR(20) NULL;
END
GO

IF COL_LENGTH('accounts', 'phone_verified_at') IS NULL
BEGIN
    ALTER TABLE accounts ADD phone_verified_at DATETIME2 NULL;
END
GO
