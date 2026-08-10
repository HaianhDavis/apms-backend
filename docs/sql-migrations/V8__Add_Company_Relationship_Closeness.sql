IF OBJECT_ID('company_relationship_closeness', 'U') IS NULL
BEGIN
    CREATE TABLE company_relationship_closeness (
        id BIGINT IDENTITY(1,1) PRIMARY KEY,
        owner_company_profile_id NVARCHAR(255) NOT NULL,
        target_company_profile_id NVARCHAR(255) NOT NULL,
        stars INT NOT NULL CHECK (stars BETWEEN 1 AND 5),
        note NVARCHAR(1000),
        rated_by_account_id BIGINT NOT NULL,
        rated_at DATETIME2 NOT NULL,
        updated_at DATETIME2 NOT NULL,
        version BIGINT NOT NULL CONSTRAINT DF_relationship_closeness_version DEFAULT 0,
        CONSTRAINT fk_relationship_closeness_account FOREIGN KEY (rated_by_account_id) REFERENCES accounts(id)
    );
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'idx_company_relationship_closeness_target' AND object_id = OBJECT_ID('company_relationship_closeness'))
BEGIN
    CREATE INDEX idx_company_relationship_closeness_target ON company_relationship_closeness (target_company_profile_id);
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'uq_company_relationship_closeness_owner_target' AND object_id = OBJECT_ID('company_relationship_closeness'))
BEGIN
    CREATE UNIQUE INDEX uq_company_relationship_closeness_owner_target ON company_relationship_closeness (owner_company_profile_id, target_company_profile_id);
END
GO
