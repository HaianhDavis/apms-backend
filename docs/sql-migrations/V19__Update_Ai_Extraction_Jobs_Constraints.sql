-- V19__Update_Ai_Extraction_Jobs_Constraints.sql
-- Update CHECK constraints on dbo.ai_extraction_jobs for status and stage to support 'CANCELLED'

-- 1. Drop existing CHECK constraint on 'stage'
DECLARE @StageConstraint NVARCHAR(200);

SELECT @StageConstraint = cc.name
FROM sys.check_constraints cc
JOIN sys.columns c ON c.object_id = cc.parent_object_id AND c.column_id = cc.parent_column_id
WHERE cc.parent_object_id = OBJECT_ID('dbo.ai_extraction_jobs') AND c.name = 'stage';

IF @StageConstraint IS NOT NULL
BEGIN
    EXEC('ALTER TABLE dbo.ai_extraction_jobs DROP CONSTRAINT [' + @StageConstraint + ']');
END;

-- 2. Drop existing CHECK constraint on 'status'
DECLARE @StatusConstraint NVARCHAR(200);

SELECT @StatusConstraint = cc.name
FROM sys.check_constraints cc
JOIN sys.columns c ON c.object_id = cc.parent_object_id AND c.column_id = cc.parent_column_id
WHERE cc.parent_object_id = OBJECT_ID('dbo.ai_extraction_jobs') AND c.name = 'status';

IF @StatusConstraint IS NOT NULL
BEGIN
    EXEC('ALTER TABLE dbo.ai_extraction_jobs DROP CONSTRAINT [' + @StatusConstraint + ']');
END;

-- 3. Add updated CHECK constraint on 'stage' including CANCELLED
ALTER TABLE dbo.ai_extraction_jobs
ADD CONSTRAINT CK_ai_extraction_jobs_stage
CHECK (
    stage IN (
        'PREPARING',
        'EXTRACTING',
        'MERGING',
        'CREATING_CANDIDATE',
        'COMPLETED',
        'FAILED',
        'CANCELLED'
    )
);

-- 4. Add updated CHECK constraint on 'status' including CANCELLED
ALTER TABLE dbo.ai_extraction_jobs
ADD CONSTRAINT CK_ai_extraction_jobs_status
CHECK (
    status IN (
        'PENDING',
        'PROCESSING',
        'COMPLETED',
        'FAILED',
        'CANCELLED'
    )
);
