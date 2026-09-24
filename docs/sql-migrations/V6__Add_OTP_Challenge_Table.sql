IF OBJECT_ID('otp_challenges', 'U') IS NULL
BEGIN
    CREATE TABLE otp_challenges (
        id BIGINT IDENTITY(1,1) PRIMARY KEY,
        account_id BIGINT NOT NULL,
        purpose NVARCHAR(50) NOT NULL,
        otp_hash NVARCHAR(255) NOT NULL,
        expires_at DATETIME2 NOT NULL,
        attempt_count INT NOT NULL DEFAULT 0,
        max_attempts INT NOT NULL DEFAULT 5,
        used_at DATETIME2 NULL,
        invalidated_at DATETIME2 NULL,
        invalidation_reason NVARCHAR(255) NULL,
        created_at DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
        request_ip NVARCHAR(45) NULL,
        CONSTRAINT fk_otp_challenge_account FOREIGN KEY (account_id) REFERENCES accounts(id)
    );
END
GO

IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_otp_challenge_account_purpose_created' AND object_id = OBJECT_ID('otp_challenges'))
BEGIN
    CREATE INDEX idx_otp_challenge_account_purpose_created ON otp_challenges (account_id, purpose, created_at DESC);
END
GO

IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_otp_challenge_expires' AND object_id = OBJECT_ID('otp_challenges'))
BEGIN
    CREATE INDEX idx_otp_challenge_expires ON otp_challenges (expires_at);
END
GO
