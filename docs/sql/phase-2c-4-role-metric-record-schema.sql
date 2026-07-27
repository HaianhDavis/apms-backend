-- Phase 2C.4: RoleMetricRecord Schema
-- Note: Uses NO ACTION for immutable history relationships and explicitly adds current_approved_version_id last to resolve circular FK.

-- 1. Create role_metric_records
CREATE TABLE role_metric_records (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    project_id BIGINT NOT NULL,
    task_id BIGINT,
    company_id VARCHAR(255) NOT NULL,
    relationship_type VARCHAR(50) NOT NULL,
    metric_key VARCHAR(255) NOT NULL,
    value_type VARCHAR(30) NOT NULL,
    period_type VARCHAR(50) NOT NULL,
    period_key VARCHAR(255) NOT NULL,
    measurement_date DATE,
    period_start DATE,
    period_end DATE,
    target_numeric_value DECIMAL(19,4),
    actual_numeric_value DECIMAL(19,4),
    target_boolean_value BIT,
    actual_boolean_value BIT,
    unit_code VARCHAR(50),
    currency_code VARCHAR(3),
    status VARCHAR(50) NOT NULL,
    submitted_by_account_id BIGINT,
    submitted_at DATETIME2,
    reviewed_by_account_id BIGINT,
    reviewed_at DATETIME2,
    review_comment NVARCHAR(MAX),
    current_approved_version_id BIGINT,
    current_approved_version_number INT,
    working_revision_number INT NOT NULL,
    optimistic_version INT NOT NULL,
    created_by_account_id BIGINT NOT NULL,
    created_at DATETIME2 NOT NULL,
    updated_at DATETIME2 NOT NULL,
    
    CONSTRAINT uq_role_metric_records_identity UNIQUE (project_id, company_id, relationship_type, metric_key, period_key)
);

-- 2. Create role_metric_record_versions
CREATE TABLE role_metric_record_versions (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    role_metric_record_id BIGINT NOT NULL,
    version_number INT NOT NULL,
    project_id BIGINT NOT NULL,
    task_id BIGINT,
    company_id VARCHAR(255) NOT NULL,
    relationship_type VARCHAR(50) NOT NULL,
    metric_key VARCHAR(255) NOT NULL,
    value_type VARCHAR(30) NOT NULL,
    period_type VARCHAR(50) NOT NULL,
    period_key VARCHAR(255) NOT NULL,
    measurement_date DATE,
    period_start DATE,
    period_end DATE,
    target_numeric_value DECIMAL(19,4),
    actual_numeric_value DECIMAL(19,4),
    target_boolean_value BIT,
    actual_boolean_value BIT,
    unit_code VARCHAR(50),
    currency_code VARCHAR(3),
    working_revision_number INT NOT NULL,
    status VARCHAR(50) NOT NULL,
    submitted_by_account_id BIGINT,
    submitted_at DATETIME2,
    reviewed_by_account_id BIGINT,
    reviewed_at DATETIME2,
    review_comment NVARCHAR(MAX),
    approved_at DATETIME2 NOT NULL,
    approved_by_account_id BIGINT NOT NULL,

    CONSTRAINT fk_role_metric_record_versions_record FOREIGN KEY (role_metric_record_id) REFERENCES role_metric_records(id) ON DELETE NO ACTION,
    CONSTRAINT uq_role_metric_record_versions_ver UNIQUE (role_metric_record_id, version_number)
);

-- 3. Create role_metric_evidences
CREATE TABLE role_metric_evidences (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    role_metric_record_id BIGINT NOT NULL,
    value_scope VARCHAR(20) NOT NULL,
    source_type VARCHAR(50) NOT NULL,
    source_contract_version_id BIGINT,
    source_clause_version_id BIGINT,
    source_raw_document_id VARCHAR(255),
    document_segment_id VARCHAR(255),
    document_hash VARCHAR(255),
    source_excerpt NVARCHAR(MAX),
    external_reference VARCHAR(1024),
    evidence_note NVARCHAR(MAX),
    optimistic_version INT NOT NULL,
    created_by_account_id BIGINT NOT NULL,
    created_at DATETIME2 NOT NULL,
    updated_by_account_id BIGINT NOT NULL,
    updated_at DATETIME2 NOT NULL,

    CONSTRAINT fk_role_metric_evidences_record FOREIGN KEY (role_metric_record_id) REFERENCES role_metric_records(id) ON DELETE CASCADE
);

-- 4. Create role_metric_evidence_versions
CREATE TABLE role_metric_evidence_versions (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    role_metric_record_version_id BIGINT NOT NULL,
    source_evidence_id BIGINT NOT NULL,
    value_scope VARCHAR(20) NOT NULL,
    source_type VARCHAR(50) NOT NULL,
    source_contract_version_id BIGINT,
    source_clause_version_id BIGINT,
    source_raw_document_id VARCHAR(255),
    document_segment_id VARCHAR(255),
    document_hash VARCHAR(255),
    source_excerpt NVARCHAR(MAX),
    external_reference VARCHAR(1024),
    evidence_note NVARCHAR(MAX),
    snapshot_at DATETIME2 NOT NULL,

    CONSTRAINT fk_role_metric_evidence_versions_version FOREIGN KEY (role_metric_record_version_id) REFERENCES role_metric_record_versions(id) ON DELETE NO ACTION
);

-- 5. Alter role_metric_records to add current_approved_version_id FK
ALTER TABLE role_metric_records
ADD CONSTRAINT fk_role_metric_records_current_version FOREIGN KEY (current_approved_version_id) REFERENCES role_metric_record_versions(id) ON DELETE NO ACTION;
