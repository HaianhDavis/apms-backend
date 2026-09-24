-- Phase 2C.3B: Partner Contract Clause Schema
-- Target: SQL Server
-- Note: Must be executed manually against production before the application boots,
-- since prod uses spring.jpa.hibernate.ddl-auto=validate.

ALTER TABLE partner_contracts ADD pending_extraction_id NVARCHAR(50);
ALTER TABLE partner_contracts ADD pending_clause_set_hash VARCHAR(128);

CREATE TABLE partner_contract_clause_versions (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    partner_contract_version_id BIGINT NOT NULL,
    clause_identity VARCHAR(100) NOT NULL,
    clause_type VARCHAR(100),
    clause_title VARCHAR(255),
    effective_date DATE,
    expiry_date DATE,
    notice_period_days INT,
    target_metric_key VARCHAR(100),
    target_value VARCHAR(255),
    target_unit VARCHAR(50),
    comparator VARCHAR(20),
    measurement_period VARCHAR(50),
    penalty_value DECIMAL(18,2),
    penalty_currency VARCHAR(3),
    penalty_description VARCHAR(500),
    source_raw_document_id VARCHAR(50),
    evidence_reference VARCHAR(255),
    source_excerpt VARCHAR(2000),
    clause_hash VARCHAR(128),
    approved_by_account_id BIGINT NOT NULL,
    approved_at DATETIME2 NOT NULL DEFAULT GETDATE(),
    CONSTRAINT uq_partner_contract_clause_versions_version_id_identity UNIQUE (partner_contract_version_id, clause_identity),
    CONSTRAINT fk_pccv_version FOREIGN KEY (partner_contract_version_id) REFERENCES partner_contract_versions(id)
);

CREATE INDEX idx_partner_contract_clause_versions_version_id ON partner_contract_clause_versions(partner_contract_version_id);

CREATE TABLE partner_contract_approval_syncs (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    extraction_draft_id NVARCHAR(50) NOT NULL,
    contract_id BIGINT NOT NULL,
    contract_version_id BIGINT NOT NULL,
    contract_version_number INT NOT NULL,
    clause_set_hash VARCHAR(128) NOT NULL,
    status VARCHAR(20) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME2,
    locked_at DATETIME2,
    locked_by VARCHAR(50),
    last_error VARCHAR(1000),
    created_at DATETIME2 NOT NULL DEFAULT GETDATE(),
    processed_at DATETIME2,
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_partner_contract_approval_syncs_contract_version UNIQUE (contract_version_id, extraction_draft_id)
);

CREATE INDEX idx_partner_contract_approval_syncs_status ON partner_contract_approval_syncs(status, next_attempt_at);
