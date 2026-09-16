-- =====================================================================================
-- V15__Add_Commercial_Evidence_Note_V4.sql
-- Description: Add commercial_evidence_note to company_relationship_assessments table
-- for Relationship Closeness Guided Qualitative Manual Scoring (V4).
-- =====================================================================================

IF NOT EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.company_relationship_assessments') AND name = 'commercial_evidence_note')
BEGIN
    ALTER TABLE dbo.company_relationship_assessments ADD commercial_evidence_note NVARCHAR(1000) NULL;
END
GO
