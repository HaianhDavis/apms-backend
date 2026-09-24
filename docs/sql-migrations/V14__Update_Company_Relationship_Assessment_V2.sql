-- =====================================================================================
-- V14__Update_Company_Relationship_Assessment_V2.sql
-- Description: Update company_relationship_assessments table for Relationship Closeness Score V2:
-- 1. Make criteria score columns nullable in DRAFT (null = not assessed yet)
-- 2. Add commercial_suggested_score, commercial_awarded_score, commercial_adjustment_reason
-- 3. Add owner_commercial_score
-- 4. Add per-criterion evidence notes (cooperation_evidence_note, strategic_evidence_note, engagement_evidence_note, qualitative_evidence_note)
-- =====================================================================================

-- 1. Drop default constraints on score columns if they exist
DECLARE @sql NVARCHAR(MAX) = '';

SELECT @sql += 'ALTER TABLE dbo.company_relationship_assessments DROP CONSTRAINT ' + dc.name + ';' + CHAR(13)
FROM sys.default_constraints dc
JOIN sys.columns c ON dc.parent_object_id = c.object_id AND dc.parent_column_id = c.column_id
WHERE dc.parent_object_id = OBJECT_ID('dbo.company_relationship_assessments')
  AND c.name IN ('cooperation_score', 'strategic_score', 'relationship_network_score', 'engagement_score', 'qualitative_score', 'commercial_score');

IF LEN(@sql) > 0
    EXEC sp_executesql @sql;
GO

-- 2. Alter columns to NULL
ALTER TABLE dbo.company_relationship_assessments ALTER COLUMN cooperation_score INT NULL;
ALTER TABLE dbo.company_relationship_assessments ALTER COLUMN strategic_score INT NULL;
ALTER TABLE dbo.company_relationship_assessments ALTER COLUMN relationship_network_score INT NULL;
ALTER TABLE dbo.company_relationship_assessments ALTER COLUMN engagement_score INT NULL;
ALTER TABLE dbo.company_relationship_assessments ALTER COLUMN qualitative_score INT NULL;
ALTER TABLE dbo.company_relationship_assessments ALTER COLUMN commercial_score INT NULL;
GO

-- 3. Add new columns if they do not exist
IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.company_relationship_assessments') AND name = 'commercial_suggested_score')
BEGIN
    ALTER TABLE dbo.company_relationship_assessments ADD commercial_suggested_score INT NULL;
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.company_relationship_assessments') AND name = 'commercial_awarded_score')
BEGIN
    ALTER TABLE dbo.company_relationship_assessments ADD commercial_awarded_score INT NULL;
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.company_relationship_assessments') AND name = 'commercial_adjustment_reason')
BEGIN
    ALTER TABLE dbo.company_relationship_assessments ADD commercial_adjustment_reason NVARCHAR(1000) NULL;
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.company_relationship_assessments') AND name = 'owner_commercial_score')
BEGIN
    ALTER TABLE dbo.company_relationship_assessments ADD owner_commercial_score INT NULL;
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.company_relationship_assessments') AND name = 'cooperation_evidence_note')
BEGIN
    ALTER TABLE dbo.company_relationship_assessments ADD cooperation_evidence_note NVARCHAR(1000) NULL;
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.company_relationship_assessments') AND name = 'strategic_evidence_note')
BEGIN
    ALTER TABLE dbo.company_relationship_assessments ADD strategic_evidence_note NVARCHAR(1000) NULL;
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.company_relationship_assessments') AND name = 'engagement_evidence_note')
BEGIN
    ALTER TABLE dbo.company_relationship_assessments ADD engagement_evidence_note NVARCHAR(1000) NULL;
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.company_relationship_assessments') AND name = 'qualitative_evidence_note')
BEGIN
    ALTER TABLE dbo.company_relationship_assessments ADD qualitative_evidence_note NVARCHAR(1000) NULL;
END
GO
