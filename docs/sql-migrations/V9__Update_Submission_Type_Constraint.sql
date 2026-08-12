-- V9__Update_Submission_Type_Constraint.sql
-- Drop the existing CHECK constraint on project_task_submissions.submission_type dynamically
-- and recreate it with the full list of supported SubmissionType values, 
-- including COMPANY_NEWS_RESEARCH.

DECLARE @ConstraintName NVARCHAR(200);

SELECT @ConstraintName = cc.name
FROM sys.check_constraints cc
JOIN sys.columns c
    ON c.object_id = cc.parent_object_id
WHERE cc.parent_object_id = OBJECT_ID('dbo.project_task_submissions')
  AND c.name = 'submission_type'
  AND cc.definition LIKE '%submission_type%';

IF @ConstraintName IS NOT NULL
BEGIN
    EXEC(
        'ALTER TABLE dbo.project_task_submissions DROP CONSTRAINT ['
        + @ConstraintName
        + ']'
    );
END;

ALTER TABLE dbo.project_task_submissions
ADD CONSTRAINT CK_project_task_submissions_submission_type
CHECK (
    submission_type IN (
        'COMPANY_CANDIDATE',
        'PROFILE_UPDATE_PROPOSAL',
        'DOCUMENT_COLLECTION',
        'COMPANY_MEMBER_RESEARCH',
        'PARTNER_CONTRACT_COLLECTION',
        'COMPANY_REPORT',
        'ROLE_EVALUATION',
        'COMPANY_NEWS_RESEARCH',
        'OTHER'
    )
);
