-- 1. Add the column if it doesn't exist
IF NOT EXISTS (
    SELECT 1 FROM sys.columns 
    WHERE object_id = OBJECT_ID('project_task_submissions') 
    AND name = 'submitted_revision_number'
)
BEGIN
    ALTER TABLE project_task_submissions ADD submitted_revision_number INT NULL;
END
GO

-- 2. Modify the check constraint on status if it exists
DECLARE @constraint_name NVARCHAR(200);
SELECT @constraint_name = chk.name 
FROM sys.check_constraints chk
INNER JOIN sys.columns col ON chk.parent_object_id = col.object_id AND chk.parent_column_id = col.column_id
WHERE chk.parent_object_id = OBJECT_ID('project_task_submissions') AND col.name = 'status';

IF @constraint_name IS NOT NULL
BEGIN
    DECLARE @sql NVARCHAR(MAX) = 'ALTER TABLE project_task_submissions DROP CONSTRAINT ' + @constraint_name;
    EXEC sp_executesql @sql;
END
GO

-- Add it back with the new value, or create it if it didn't exist
IF NOT EXISTS (
    SELECT 1 FROM sys.check_constraints 
    WHERE name = 'CHK_project_task_submissions_status' 
    AND parent_object_id = OBJECT_ID('project_task_submissions')
)
BEGIN
    ALTER TABLE project_task_submissions ADD CONSTRAINT CHK_project_task_submissions_status 
    CHECK (status IN ('DRAFT', 'SUBMITTED', 'IN_REVIEW', 'APPROVED', 'REJECTED', 'APPLIED', 'REVISION_REQUESTED'));
END
GO

-- 3. Duplicate check before unique index creation
IF EXISTS (
    SELECT project_task_id, target_entity_type, target_entity_id, submitted_revision_number
    FROM project_task_submissions
    WHERE submitted_revision_number IS NOT NULL
    GROUP BY project_task_id, target_entity_type, target_entity_id, submitted_revision_number
    HAVING COUNT(*) > 1
)
BEGIN
    RAISERROR ('Cannot create unique index UQ_ProjectTaskSubmission_TargetEntityRev due to existing duplicate submissions.', 16, 1);
    RETURN;
END
GO

-- 4. Create unique index
IF NOT EXISTS (
    SELECT 1 FROM sys.indexes 
    WHERE name = 'UQ_ProjectTaskSubmission_TargetEntityRev' 
    AND object_id = OBJECT_ID('project_task_submissions')
)
BEGIN
    CREATE UNIQUE INDEX UQ_ProjectTaskSubmission_TargetEntityRev 
    ON project_task_submissions (project_task_id, target_entity_type, target_entity_id, submitted_revision_number) 
    WHERE submitted_revision_number IS NOT NULL;
END
GO
