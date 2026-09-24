-- Phase 2C.3A: Partner Contract Schema
-- Target: SQL Server
-- Note: Must be executed manually against production before the application boots,
-- since prod uses spring.jpa.hibernate.ddl-auto=validate.

CREATE TABLE partner_contracts (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    reference_company_id VARCHAR(36) NOT NULL,
    partner_company_id VARCHAR(36) NOT NULL,
    source_project_id BIGINT NOT NULL,
    source_task_id BIGINT,
    raw_document_id VARCHAR(50),
    contract_number VARCHAR(100),
    contract_title VARCHAR(255),
    contract_type VARCHAR(100),
    review_status VARCHAR(30) NOT NULL,
    lifecycle_status VARCHAR(30),
    signed_date DATE,
    effective_date DATE,
    expiry_date DATE,
    currency VARCHAR(3),
    total_contract_value DECIMAL(18,2),
    current_version INT NOT NULL DEFAULT 0,
    created_by_account_id BIGINT NOT NULL,
    created_at DATETIME2 NOT NULL DEFAULT GETDATE(),
    updated_by_account_id BIGINT,
    updated_at DATETIME2,
    approved_by_account_id BIGINT,
    approved_at DATETIME2,
    version INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_partner_contracts_source_project_id ON partner_contracts(source_project_id);
CREATE INDEX idx_partner_contracts_partner_company_id ON partner_contracts(partner_company_id);
CREATE INDEX idx_partner_contracts_review_status ON partner_contracts(review_status);
CREATE INDEX idx_partner_contracts_lifecycle_status ON partner_contracts(lifecycle_status);

CREATE TABLE partner_contract_versions (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    contract_id BIGINT NOT NULL,
    reference_company_id VARCHAR(36) NOT NULL,
    partner_company_id VARCHAR(36) NOT NULL,
    source_project_id BIGINT NOT NULL,
    source_task_id BIGINT,
    raw_document_id VARCHAR(50),
    contract_number VARCHAR(100),
    contract_title VARCHAR(255),
    contract_type VARCHAR(100),
    review_status VARCHAR(30) NOT NULL,
    lifecycle_status VARCHAR(30),
    signed_date DATE,
    effective_date DATE,
    expiry_date DATE,
    currency VARCHAR(3),
    total_contract_value DECIMAL(18,2),
    created_by_account_id BIGINT NOT NULL,
    created_at DATETIME2 NOT NULL,
    updated_by_account_id BIGINT,
    updated_at DATETIME2,
    approved_by_account_id BIGINT,
    approved_at DATETIME2,
    version INT NOT NULL,
    CONSTRAINT uq_partner_contract_versions_contract_id_version UNIQUE (contract_id, version)
);
