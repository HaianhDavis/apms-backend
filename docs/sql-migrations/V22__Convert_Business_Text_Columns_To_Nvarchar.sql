-- =====================================================================================
-- Migration: V22__Convert_Business_Text_Columns_To_Nvarchar.sql
-- Description: Convert user-visible and business text columns in SQL Server from
--              VARCHAR to NVARCHAR for complete preservation of Vietnamese and other
--              multilingual Unicode characters.
--
-- Safety & Idempotency:
-- - Uses INFORMATION_SCHEMA.COLUMNS to only alter columns that are currently 'varchar'.
-- - Preserves exact column lengths and NULL/NOT NULL constraints.
-- - No indexes or constraints are altered, as none of the target columns participate
--   in primary keys, unique constraints, foreign keys, or indexes.
-- =====================================================================================

-- 1. Table: projects
-- 1.1 project_name: NVARCHAR(255) NOT NULL
IF EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'projects'
      AND COLUMN_NAME = 'project_name'
      AND DATA_TYPE = 'varchar'
)
BEGIN
    ALTER TABLE projects ALTER COLUMN project_name NVARCHAR(255) NOT NULL;
    PRINT 'Updated projects.project_name to NVARCHAR(255) NOT NULL';
END;

-- 1.2 target_company_name: NVARCHAR(255) NOT NULL
IF EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'projects'
      AND COLUMN_NAME = 'target_company_name'
      AND DATA_TYPE = 'varchar'
)
BEGIN
    ALTER TABLE projects ALTER COLUMN target_company_name NVARCHAR(255) NOT NULL;
    PRINT 'Updated projects.target_company_name to NVARCHAR(255) NOT NULL';
END;

-- 2. Table: project_tasks
-- 2.1 title: NVARCHAR(255) NOT NULL
IF EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'project_tasks'
      AND COLUMN_NAME = 'title'
      AND DATA_TYPE = 'varchar'
)
BEGIN
    ALTER TABLE project_tasks ALTER COLUMN title NVARCHAR(255) NOT NULL;
    PRINT 'Updated project_tasks.title to NVARCHAR(255) NOT NULL';
END;

-- 3. Table: project_key_results
-- 3.1 name: NVARCHAR(255) NOT NULL
IF EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'project_key_results'
      AND COLUMN_NAME = 'name'
      AND DATA_TYPE = 'varchar'
)
BEGIN
    ALTER TABLE project_key_results ALTER COLUMN name NVARCHAR(255) NOT NULL;
    PRINT 'Updated project_key_results.name to NVARCHAR(255) NOT NULL';
END;

-- 4. Table: users
-- 4.1 first_name: NVARCHAR(255) NULL
IF EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'users'
      AND COLUMN_NAME = 'first_name'
      AND DATA_TYPE = 'varchar'
)
BEGIN
    ALTER TABLE users ALTER COLUMN first_name NVARCHAR(255) NULL;
    PRINT 'Updated users.first_name to NVARCHAR(255) NULL';
END;

-- 4.2 last_name: NVARCHAR(255) NULL
IF EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'users'
      AND COLUMN_NAME = 'last_name'
      AND DATA_TYPE = 'varchar'
)
BEGIN
    ALTER TABLE users ALTER COLUMN last_name NVARCHAR(255) NULL;
    PRINT 'Updated users.last_name to NVARCHAR(255) NULL';
END;

-- 4.3 department: NVARCHAR(255) NULL
IF EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'users'
      AND COLUMN_NAME = 'department'
      AND DATA_TYPE = 'varchar'
)
BEGIN
    ALTER TABLE users ALTER COLUMN department NVARCHAR(255) NULL;
    PRINT 'Updated users.department to NVARCHAR(255) NULL';
END;

-- 4.4 position: NVARCHAR(255) NULL
IF EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'users'
      AND COLUMN_NAME = 'position'
      AND DATA_TYPE = 'varchar'
)
BEGIN
    ALTER TABLE users ALTER COLUMN position NVARCHAR(255) NULL;
    PRINT 'Updated users.position to NVARCHAR(255) NULL';
END;

-- 4.5 address: NVARCHAR(255) NULL
IF EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'users'
      AND COLUMN_NAME = 'address'
      AND DATA_TYPE = 'varchar'
)
BEGIN
    ALTER TABLE users ALTER COLUMN address NVARCHAR(255) NULL;
    PRINT 'Updated users.address to NVARCHAR(255) NULL';
END;

-- 5. Table: import_jobs
-- 5.1 file_name: NVARCHAR(255) NULL
IF EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'import_jobs'
      AND COLUMN_NAME = 'file_name'
      AND DATA_TYPE = 'varchar'
)
BEGIN
    ALTER TABLE import_jobs ALTER COLUMN file_name NVARCHAR(255) NULL;
    PRINT 'Updated import_jobs.file_name to NVARCHAR(255) NULL';
END;

-- 6. Table: partner_contracts
-- 6.1 contract_title: NVARCHAR(255) NULL
IF EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'partner_contracts'
      AND COLUMN_NAME = 'contract_title'
      AND DATA_TYPE = 'varchar'
)
BEGIN
    ALTER TABLE partner_contracts ALTER COLUMN contract_title NVARCHAR(255) NULL;
    PRINT 'Updated partner_contracts.contract_title to NVARCHAR(255) NULL';
END;

-- 6.2 contract_type: NVARCHAR(100) NULL
IF EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'partner_contracts'
      AND COLUMN_NAME = 'contract_type'
      AND DATA_TYPE = 'varchar'
)
BEGIN
    ALTER TABLE partner_contracts ALTER COLUMN contract_type NVARCHAR(100) NULL;
    PRINT 'Updated partner_contracts.contract_type to NVARCHAR(100) NULL';
END;

-- 7. Table: partner_contract_versions
-- 7.1 contract_title: NVARCHAR(255) NULL
IF EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'partner_contract_versions'
      AND COLUMN_NAME = 'contract_title'
      AND DATA_TYPE = 'varchar'
)
BEGIN
    ALTER TABLE partner_contract_versions ALTER COLUMN contract_title NVARCHAR(255) NULL;
    PRINT 'Updated partner_contract_versions.contract_title to NVARCHAR(255) NULL';
END;

-- 7.2 contract_type: NVARCHAR(100) NULL
IF EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'partner_contract_versions'
      AND COLUMN_NAME = 'contract_type'
      AND DATA_TYPE = 'varchar'
)
BEGIN
    ALTER TABLE partner_contract_versions ALTER COLUMN contract_type NVARCHAR(100) NULL;
    PRINT 'Updated partner_contract_versions.contract_type to NVARCHAR(100) NULL';
END;

-- 8. Table: partner_contract_clause_versions
-- 8.1 clause_title: NVARCHAR(255) NULL
IF EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'partner_contract_clause_versions'
      AND COLUMN_NAME = 'clause_title'
      AND DATA_TYPE = 'varchar'
)
BEGIN
    ALTER TABLE partner_contract_clause_versions ALTER COLUMN clause_title NVARCHAR(255) NULL;
    PRINT 'Updated partner_contract_clause_versions.clause_title to NVARCHAR(255) NULL';
END;

-- 8.2 target_value: NVARCHAR(255) NULL
IF EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'partner_contract_clause_versions'
      AND COLUMN_NAME = 'target_value'
      AND DATA_TYPE = 'varchar'
)
BEGIN
    ALTER TABLE partner_contract_clause_versions ALTER COLUMN target_value NVARCHAR(255) NULL;
    PRINT 'Updated partner_contract_clause_versions.target_value to NVARCHAR(255) NULL';
END;

-- 8.3 penalty_description: NVARCHAR(500) NULL
IF EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'partner_contract_clause_versions'
      AND COLUMN_NAME = 'penalty_description'
      AND DATA_TYPE = 'varchar'
)
BEGIN
    ALTER TABLE partner_contract_clause_versions ALTER COLUMN penalty_description NVARCHAR(500) NULL;
    PRINT 'Updated partner_contract_clause_versions.penalty_description to NVARCHAR(500) NULL';
END;

-- 8.4 evidence_reference: NVARCHAR(255) NULL
IF EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'partner_contract_clause_versions'
      AND COLUMN_NAME = 'evidence_reference'
      AND DATA_TYPE = 'varchar'
)
BEGIN
    ALTER TABLE partner_contract_clause_versions ALTER COLUMN evidence_reference NVARCHAR(255) NULL;
    PRINT 'Updated partner_contract_clause_versions.evidence_reference to NVARCHAR(255) NULL';
END;

-- 8.5 source_excerpt: NVARCHAR(2000) NULL
IF EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'partner_contract_clause_versions'
      AND COLUMN_NAME = 'source_excerpt'
      AND DATA_TYPE = 'varchar'
)
BEGIN
    ALTER TABLE partner_contract_clause_versions ALTER COLUMN source_excerpt NVARCHAR(2000) NULL;
    PRINT 'Updated partner_contract_clause_versions.source_excerpt to NVARCHAR(2000) NULL';
END;

-- 9. Table: company_relationship_closeness
-- 9.1 note: NVARCHAR(1000) NULL
IF EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'company_relationship_closeness'
      AND COLUMN_NAME = 'note'
      AND DATA_TYPE = 'varchar'
)
BEGIN
    ALTER TABLE company_relationship_closeness ALTER COLUMN note NVARCHAR(1000) NULL;
    PRINT 'Updated company_relationship_closeness.note to NVARCHAR(1000) NULL';
END;

-- 9.2 manager_note: NVARCHAR(1000) NULL
IF EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'company_relationship_closeness'
      AND COLUMN_NAME = 'manager_note'
      AND DATA_TYPE = 'varchar'
)
BEGIN
    ALTER TABLE company_relationship_closeness ALTER COLUMN manager_note NVARCHAR(1000) NULL;
    PRINT 'Updated company_relationship_closeness.manager_note to NVARCHAR(1000) NULL';
END;

-- 9.3 owner_note: NVARCHAR(1000) NULL
IF EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'company_relationship_closeness'
      AND COLUMN_NAME = 'owner_note'
      AND DATA_TYPE = 'varchar'
)
BEGIN
    ALTER TABLE company_relationship_closeness ALTER COLUMN owner_note NVARCHAR(1000) NULL;
    PRINT 'Updated company_relationship_closeness.owner_note to NVARCHAR(1000) NULL';
END;
