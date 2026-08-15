IF COL_LENGTH('accounts', 'email_verified') IS NULL
BEGIN
    ALTER TABLE accounts ADD email_verified BIT NOT NULL CONSTRAINT df_accounts_email_verified DEFAULT 1;
END
GO

IF COL_LENGTH('otp_challenges', 'verification_ticket_hash') IS NULL
BEGIN
    ALTER TABLE otp_challenges ADD verification_ticket_hash NVARCHAR(255) NULL;
END
GO

IF COL_LENGTH('otp_challenges', 'ticket_expires_at') IS NULL
BEGIN
    ALTER TABLE otp_challenges ADD ticket_expires_at DATETIME2 NULL;
END
GO
