-- =====================================================================================
-- V16__Add_Assessment_Type_And_Source_Assessment_Id.sql
-- Description: Add assessment_type and source_assessment_id to company_relationship_assessments table
-- to explicitly distinguish MANAGER_ASSESSMENT vs OWNER_ADJUSTMENT versions and preserve traceability.
-- =====================================================================================

IF NOT EXISTS (
    SELECT 1 FROM sys.columns 
    WHERE object_id = OBJECT_ID('dbo.company_relationship_assessments') 
      AND name = 'assessment_type'
)
BEGIN
    ALTER TABLE dbo.company_relationship_assessments 
    ADD assessment_type NVARCHAR(30) NOT NULL 
    CONSTRAINT DF_rel_assess_type DEFAULT 'MANAGER_ASSESSMENT';
END
GO

IF NOT EXISTS (
    SELECT 1 FROM sys.columns 
    WHERE object_id = OBJECT_ID('dbo.company_relationship_assessments') 
      AND name = 'source_assessment_id'
)
BEGIN
    ALTER TABLE dbo.company_relationship_assessments 
    ADD source_assessment_id BIGINT NULL;
END
GO
