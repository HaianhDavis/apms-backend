-- docs/sql/phase-1-role-scoring-schema.sql
-- Description: Schema changes for Phase 1 of Canonical Role Scoring.
-- DB Engine: SQL Server (Compatible)
-- Action: These statements document the schema requirements. 
-- IMPORTANT: Do not drop existing legacy tables/columns. Do not alter existing legacy records.

-- 1. Create RoleScoreRuleSet Table
CREATE TABLE role_score_rule_sets (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    evaluated_role VARCHAR(255) NOT NULL,
    rule_set_version VARCHAR(255) NOT NULL,
    weighting_method VARCHAR(255) NOT NULL,
    weight_source VARCHAR(255) NOT NULL,
    weight_version VARCHAR(255) NOT NULL,
    is_active BIT NOT NULL,
    effective_from DATETIME2,
    effective_to DATETIME2,
    created_by_account_id BIGINT,
    created_at DATETIME2 NOT NULL DEFAULT GETDATE(),
    updated_at DATETIME2,
    CONSTRAINT uq_role_score_rule_sets_role_version UNIQUE (evaluated_role, rule_set_version)
);

-- Enforce one active rule set per role
CREATE UNIQUE INDEX ux_role_score_rule_sets_one_active_per_role
ON role_score_rule_sets(evaluated_role)
WHERE is_active = 1;

-- 2. Create RoleCriterionRule Table
CREATE TABLE role_criterion_rules (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    rule_set_id BIGINT NOT NULL,
    criterion_key VARCHAR(255) NOT NULL,
    criterion_name VARCHAR(255) NOT NULL,
    weight DECIMAL(8,6) NOT NULL,
    direction VARCHAR(255) NOT NULL,
    is_required BIT NOT NULL,
    scoring_method VARCHAR(255),
    rule_definition_json NVARCHAR(MAX),
    display_order INT NOT NULL,
    is_active BIT NOT NULL,
    created_at DATETIME2 NOT NULL DEFAULT GETDATE(),
    updated_at DATETIME2,
    CONSTRAINT fk_criterion_rules_rule_set FOREIGN KEY (rule_set_id) REFERENCES role_score_rule_sets(id),
    CONSTRAINT uq_role_criterion_rules_set_key UNIQUE (rule_set_id, criterion_key)
);

-- 3. Alter existing ScoreSnapshot Table to make legacy properties nullable for canonical row compatibility
ALTER TABLE score_snapshots ALTER COLUMN company_id VARCHAR(36) NULL;
ALTER TABLE score_snapshots ALTER COLUMN project_id BIGINT NULL;
ALTER TABLE score_snapshots ALTER COLUMN candidate_id VARCHAR(255) NULL;
ALTER TABLE score_snapshots ALTER COLUMN rule_version VARCHAR(255) NULL;

-- 4. Extend ScoreSnapshot Table with Canonical Fields
ALTER TABLE score_snapshots ADD target_company_profile_id VARCHAR(255);
ALTER TABLE score_snapshots ADD target_profile_version INT;
ALTER TABLE score_snapshots ADD reference_company_profile_id VARCHAR(255);
ALTER TABLE score_snapshots ADD reference_profile_version INT;
ALTER TABLE score_snapshots ADD evaluated_role VARCHAR(255); -- evaluated_role IS NULL -> legacy row, evaluated_role IS NOT NULL -> canonical row
ALTER TABLE score_snapshots ADD role_score_rule_set_id BIGINT;
ALTER TABLE score_snapshots ADD score_rule_set_version VARCHAR(255);
ALTER TABLE score_snapshots ADD weighting_method VARCHAR(255);
ALTER TABLE score_snapshots ADD weight_source VARCHAR(255);
ALTER TABLE score_snapshots ADD weight_version VARCHAR(255);
ALTER TABLE score_snapshots ADD criterion_scores_json NVARCHAR(MAX);
ALTER TABLE score_snapshots ADD normalized_criterion_scores_json NVARCHAR(MAX);
ALTER TABLE score_snapshots ADD weights_used_json NVARCHAR(MAX);
ALTER TABLE score_snapshots ADD overall_score DECIMAL(7,2);
ALTER TABLE score_snapshots ADD completeness_status VARCHAR(255);
ALTER TABLE score_snapshots ADD missing_criteria_json NVARCHAR(MAX);
ALTER TABLE score_snapshots ADD evidence_refs_json NVARCHAR(MAX);
ALTER TABLE score_snapshots ADD calculated_by_account_id BIGINT;
ALTER TABLE score_snapshots ADD calculated_at DATETIME2;

-- 5. Foreign keys for canonical extensions
ALTER TABLE score_snapshots ADD CONSTRAINT fk_score_snapshots_role_set FOREIGN KEY (role_score_rule_set_id) REFERENCES role_score_rule_sets(id);

-- 6. Indexes for canonical queries
CREATE INDEX idx_score_snapshots_target_role_calc ON score_snapshots(target_company_profile_id, evaluated_role, calculated_at DESC);
