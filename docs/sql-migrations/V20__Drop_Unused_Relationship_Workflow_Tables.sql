-- ============================================================================
-- Migration: V20__Drop_Unused_Relationship_Workflow_Tables.sql
-- Description: Drop obsolete relationship workflow tables:
--              1. company_relationship_history
--              2. company_relationship_change_proposals
--
-- Notes:
--   - Canonical company relationships are stored in Neo4j.
--   - General system audit remains available via audit_logs.
--   - KEPT:
--       company_relationship_closeness
--       company_relationship_assessments
--       company_monitoring_assignments
--       company_monitoring_reviews
--       partner_contracts and contract version tables
-- ============================================================================

-- Drop any foreign key constraints referencing or originating from the target tables
DECLARE @sql NVARCHAR(MAX) = N'';
SELECT @sql += N'ALTER TABLE ' + QUOTENAME(OBJECT_SCHEMA_NAME(parent_object_id)) + '.' + QUOTENAME(OBJECT_NAME(parent_object_id)) + 
               ' DROP CONSTRAINT ' + QUOTENAME(name) + ';' + CHAR(13)
FROM sys.foreign_keys
WHERE referenced_object_id IN (OBJECT_ID('dbo.company_relationship_change_proposals'), OBJECT_ID('dbo.company_relationship_history'))
   OR parent_object_id IN (OBJECT_ID('dbo.company_relationship_change_proposals'), OBJECT_ID('dbo.company_relationship_history'));

IF @sql <> N''
BEGIN
    EXEC sp_executesql @sql;
    PRINT 'Dropped foreign keys associated with relationship workflow tables';
END;

IF OBJECT_ID('dbo.company_relationship_history', 'U') IS NOT NULL
BEGIN
    DROP TABLE dbo.company_relationship_history;
    PRINT 'Dropped table dbo.company_relationship_history';
END;

IF OBJECT_ID('dbo.company_relationship_change_proposals', 'U') IS NOT NULL
BEGIN
    DROP TABLE dbo.company_relationship_change_proposals;
    PRINT 'Dropped table dbo.company_relationship_change_proposals';
END;


IF COL_LENGTH(
    'dbo.company_monitoring_reviews',
    'relationship_change_proposal_id'
) IS NOT NULL
BEGIN
    ALTER TABLE dbo.company_monitoring_reviews
    DROP COLUMN relationship_change_proposal_id;
END;