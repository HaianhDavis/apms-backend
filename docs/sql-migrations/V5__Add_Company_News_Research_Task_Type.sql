-- V5__Add_Company_News_Research_Task_Type.sql
-- Adds COMPANY_NEWS_RESEARCH to the task_type CHECK constraint on project_tasks.
-- Must be run by a DBA or applied before starting the new backend version.

-- Step 1: Drop the existing CHECK constraint
ALTER TABLE project_tasks DROP CONSTRAINT CK__project_t__task___5CD6CB2B;

-- Step 2: Re-create with COMPANY_NEWS_RESEARCH included
ALTER TABLE project_tasks ADD CONSTRAINT CK_project_tasks_task_type
CHECK (task_type IN (
    'GENERAL_TASK',
    'DOCUMENT_COLLECTION',
    'COMPANY_DATA_PREPARATION',
    'ROLE_EVALUATION',
    'COMPANY_MEMBER_RESEARCH',
    'COMPANY_NEWS_RESEARCH',
    'PARTNER_CONTRACT_COLLECTION'
));
