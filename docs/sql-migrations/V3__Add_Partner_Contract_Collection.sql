-- Add target_company_profile_id to project_tasks
ALTER TABLE project_tasks
ADD target_company_profile_id VARCHAR(255) NULL;

-- Add lineage and ownership fields to partner_contracts
ALTER TABLE partner_contracts
ADD source_project_id BIGINT NULL,
    source_task_id BIGINT NULL,
    source_submission_id BIGINT NULL,
    extraction_draft_id VARCHAR(50) NULL,
    owner_company_profile_id VARCHAR(36) NULL,
    raw_document_id VARCHAR(50) NULL,
    approved_by_account_id BIGINT NULL,
    approved_at DATETIME2 NULL;

-- Create indexes for efficient querying
CREATE INDEX idx_project_tasks_target_company_profile_id ON project_tasks (target_company_profile_id);
CREATE INDEX idx_partner_contracts_owner_company_profile_id ON partner_contracts (owner_company_profile_id);
CREATE INDEX idx_partner_contracts_extraction_draft_id ON partner_contracts (extraction_draft_id);
CREATE INDEX idx_partner_contracts_source_task_id ON partner_contracts (source_task_id);
CREATE INDEX idx_partner_contracts_source_submission_id ON partner_contracts (source_submission_id);
