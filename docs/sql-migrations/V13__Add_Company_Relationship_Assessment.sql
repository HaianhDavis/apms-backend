-- =====================================================================================
-- V13__Add_Company_Relationship_Assessment.sql
-- Description: Create company_relationship_assessments table for 0-100 Relationship Closeness Score V1.
-- Includes filtered unique index to guarantee at most one active assessment per (owner, target) pair.
-- =====================================================================================

IF OBJECT_ID('dbo.company_relationship_assessments', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.company_relationship_assessments (
        id BIGINT IDENTITY(1,1) PRIMARY KEY,
        owner_company_profile_id NVARCHAR(255) NOT NULL,
        company_profile_id NVARCHAR(255) NOT NULL,
        version_number INT NOT NULL,
        status NVARCHAR(30) NOT NULL, -- DRAFT, SUBMITTED, CHANGES_REQUESTED, FINALIZED
        scoring_policy_version NVARCHAR(50) NOT NULL CONSTRAINT DF_rel_assess_policy_ver DEFAULT 'RELATIONSHIP_CLOSENESS_V1',

        -- Commercial Evidence Snapshot (Frozen once submitted)
        commercial_score INT NOT NULL CONSTRAINT DF_rel_assess_comm_score DEFAULT 0,
        contract_value_score INT NULL,
        contract_count_score INT NOT NULL CONSTRAINT DF_rel_assess_count_score DEFAULT 0,
        relationship_duration_score INT NOT NULL CONSTRAINT DF_rel_assess_dur_score DEFAULT 0,
        contract_recency_score INT NOT NULL CONSTRAINT DF_rel_assess_rec_score DEFAULT 0,
        approved_contract_count INT NOT NULL CONSTRAINT DF_rel_assess_appr_count DEFAULT 0,
        total_contract_value_vnd DECIMAL(18,2) NULL,
        contract_currencies NVARCHAR(200) NULL,
        currency_breakdown NVARCHAR(1000) NULL,
        contract_value_status NVARCHAR(30) NOT NULL CONSTRAINT DF_rel_assess_cv_status DEFAULT 'SCORABLE',
        first_cooperation_date DATE NULL,
        latest_contract_date DATE NULL,
        upcoming_contract_count INT NOT NULL CONSTRAINT DF_rel_assess_upc_count DEFAULT 0,

        -- Normalization Metadata
        scorable_base INT NOT NULL CONSTRAINT DF_rel_assess_base DEFAULT 100,
        normalization_applied BIT NOT NULL CONSTRAINT DF_rel_assess_norm DEFAULT 0,

        -- Manager Human Assessment & Notes
        cooperation_score INT NOT NULL CONSTRAINT DF_rel_assess_mgr_coop DEFAULT 0,
        strategic_score INT NOT NULL CONSTRAINT DF_rel_assess_mgr_strat DEFAULT 0,
        relationship_network_score INT NOT NULL CONSTRAINT DF_rel_assess_mgr_net DEFAULT 0,
        relationship_network_note NVARCHAR(500) NULL,
        engagement_score INT NOT NULL CONSTRAINT DF_rel_assess_mgr_eng DEFAULT 0,
        qualitative_score INT NOT NULL CONSTRAINT DF_rel_assess_mgr_qual DEFAULT 0,
        manager_note NVARCHAR(2000) NULL,
        manager_raw_scorable_score INT NULL,
        manager_total_score INT NULL,
        manager_rank NVARCHAR(5) NULL,
        manager_account_id BIGINT NULL,
        manager_submitted_at DATETIME2 NULL,

        -- Changes Requested Audit
        changes_requested_reason NVARCHAR(2000) NULL,
        changes_requested_by_account_id BIGINT NULL,
        changes_requested_at DATETIME2 NULL,

        -- Owner Final Assessment & Overrides
        owner_cooperation_score INT NULL,
        owner_strategic_score INT NULL,
        owner_relationship_network_score INT NULL,
        owner_relationship_network_note NVARCHAR(500) NULL,
        owner_engagement_score INT NULL,
        owner_qualitative_score INT NULL,
        owner_note NVARCHAR(2000) NULL,
        owner_adjustment_reason NVARCHAR(2000) NULL,
        owner_raw_scorable_score INT NULL,
        owner_final_total_score INT NULL,
        owner_final_rank NVARCHAR(5) NULL,
        owner_account_id BIGINT NULL,
        finalized_at DATETIME2 NULL,

        -- System Audit & Concurrency
        created_by_account_id BIGINT NOT NULL,
        created_at DATETIME2 NOT NULL CONSTRAINT DF_rel_assess_created DEFAULT SYSUTCDATETIME(),
        updated_at DATETIME2 NOT NULL CONSTRAINT DF_rel_assess_updated DEFAULT SYSUTCDATETIME(),
        version BIGINT NOT NULL CONSTRAINT DF_rel_assess_version DEFAULT 0
    );
END
GO

-- 1. Filtered Unique Index: Guarantees at most ONE active assessment per (owner, target) pair
IF NOT EXISTS (
    SELECT 1 FROM sys.indexes 
    WHERE name = 'uq_active_company_relationship_assessment' 
      AND object_id = OBJECT_ID('dbo.company_relationship_assessments')
)
BEGIN
    CREATE UNIQUE INDEX uq_active_company_relationship_assessment
    ON dbo.company_relationship_assessments (owner_company_profile_id, company_profile_id)
    WHERE status IN ('DRAFT', 'SUBMITTED', 'CHANGES_REQUESTED');
END
GO

-- 2. Version Uniqueness Index: Guarantees unique version number per (owner, target) pair
IF NOT EXISTS (
    SELECT 1 FROM sys.indexes 
    WHERE name = 'uq_company_relationship_assessment_version' 
      AND object_id = OBJECT_ID('dbo.company_relationship_assessments')
)
BEGIN
    CREATE UNIQUE INDEX uq_company_relationship_assessment_version
    ON dbo.company_relationship_assessments (owner_company_profile_id, company_profile_id, version_number);
END
GO

-- 3. Lookup Index: Fast queries by company_profile_id and status
IF NOT EXISTS (
    SELECT 1 FROM sys.indexes 
    WHERE name = 'idx_company_relationship_assessments_target' 
      AND object_id = OBJECT_ID('dbo.company_relationship_assessments')
)
BEGIN
    CREATE INDEX idx_company_relationship_assessments_target
    ON dbo.company_relationship_assessments (company_profile_id, status);
END
GO
