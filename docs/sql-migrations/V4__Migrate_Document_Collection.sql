-- V4__Migrate_Document_Collection.sql
-- Manual migration script to update existing project_tasks
-- Must be run by a DBA before starting the new backend version.

UPDATE project_tasks
SET task_type = 'COMPANY_DATA_PREPARATION'
WHERE task_type = 'DOCUMENT_COLLECTION';

-- Verification query
-- SELECT COUNT(*)
-- FROM project_tasks
-- WHERE task_type = 'DOCUMENT_COLLECTION';
