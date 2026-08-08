-- Add planned_end_date to projects table
IF COL_LENGTH('projects', 'planned_end_date') IS NULL
BEGIN
    ALTER TABLE projects
    ADD planned_end_date DATE NULL;
END;

-- Verification query
-- SELECT id, project_name
-- FROM projects
-- WHERE planned_end_date IS NULL;
