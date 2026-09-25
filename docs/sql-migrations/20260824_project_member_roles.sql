-- ==============================================================================
-- MIGRATION SCRIPT: Project Member Governance Roles (LEADER / DEPUTY / MEMBER)
-- Execution: Manual
-- Purpose: Migrates legacy member_role to project_role, ensuring exactly one LEADER
-- ==============================================================================

USE apms;
GO

-- ==============================================================================
-- A. PRE-MIGRATION AUDIT
-- ==============================================================================

PRINT '=== PRE-MIGRATION AUDIT ===';

-- 1. Total Projects
SELECT COUNT(*) AS total_projects FROM projects;

-- 2. Total Memberships
SELECT COUNT(*) AS total_memberships FROM project_members;

-- 3. Current Project Roles (New)
SELECT project_role, COUNT(*) AS count 
FROM project_members 
GROUP BY project_role;

-- 4. Current Legacy Roles (Old, if column exists)
IF COL_LENGTH('project_members', 'member_role') IS NOT NULL
BEGIN
    EXEC('SELECT member_role, COUNT(*) AS count FROM project_members GROUP BY member_role');
END

-- 5. Projects missing creator in project_members
SELECT p.id AS project_id, p.project_name, p.created_by_account_id
FROM projects p
LEFT JOIN project_members pm ON p.id = pm.project_id AND p.created_by_account_id = pm.account_id
WHERE pm.id IS NULL;
PRINT 'NOTE: If the above query returns rows, the migration will NOT artificially invent LEADER memberships for those projects.';

-- 6. Projects with multiple legacy MANAGER rows (if column exists)
IF COL_LENGTH('project_members', 'member_role') IS NOT NULL
BEGIN
    EXEC('
    SELECT project_id, COUNT(*) as manager_count 
    FROM project_members 
    WHERE member_role = ''MANAGER'' 
    GROUP BY project_id 
    HAVING COUNT(*) > 1;
    ');
END

-- ==============================================================================
-- B. MIGRATION
-- ==============================================================================
PRINT '=== BEGIN MIGRATION ===';

BEGIN TRY
    BEGIN TRANSACTION;

    -- 1. Creator membership -> LEADER
    UPDATE pm
    SET pm.project_role = 'LEADER'
    FROM project_members pm
    INNER JOIN projects p ON p.id = pm.project_id
    WHERE pm.account_id = p.created_by_account_id;

    -- 2. All other memberships -> MEMBER
    UPDATE pm
    SET pm.project_role = 'MEMBER'
    FROM project_members pm
    INNER JOIN projects p ON p.id = pm.project_id
    WHERE pm.account_id <> p.created_by_account_id
      AND pm.project_role <> 'LEADER';

    -- ==============================================================================
    -- C. UNIQUE INDEX
    -- Documenting semantics:
    -- UQ_Project_Leader guarantees AT MOST one LEADER per project.
    -- It does NOT guarantee a Project has a Leader. (Service logic guarantees "at least one").
    -- ==============================================================================
    
    IF NOT EXISTS (
        SELECT * FROM sys.indexes 
        WHERE name = 'UQ_Project_Leader' 
        AND object_id = OBJECT_ID('project_members')
    )
    BEGIN
        CREATE UNIQUE INDEX UQ_Project_Leader 
        ON project_members(project_id) 
        WHERE project_role = 'LEADER';
        PRINT 'Created UNIQUE INDEX UQ_Project_Leader';
    END
    ELSE
    BEGIN
        PRINT 'Index UQ_Project_Leader already exists';
    END

    -- ==============================================================================
    -- D. POST-MIGRATION VERIFICATION
    -- ==============================================================================
    PRINT '=== POST-MIGRATION VERIFICATION ===';

    DECLARE @zero_leader_count INT;
    DECLARE @multi_leader_count INT;

    -- Verify: Projects with zero LEADER (ignoring projects with 0 members)
    SELECT @zero_leader_count = COUNT(*)
    FROM projects p
    WHERE EXISTS (SELECT 1 FROM project_members WHERE project_id = p.id)
      AND NOT EXISTS (
        SELECT 1 FROM project_members pm 
        WHERE pm.project_id = p.id AND pm.project_role = 'LEADER'
    );

    IF @zero_leader_count > 0
    BEGIN
        RAISERROR('Verification Failed: Found %d projects with members but ZERO leaders.', 16, 1, @zero_leader_count);
    END

    -- Verify: Projects with more than one LEADER
    SELECT @multi_leader_count = COUNT(*)
    FROM (
        SELECT project_id 
        FROM project_members 
        WHERE project_role = 'LEADER' 
        GROUP BY project_id 
        HAVING COUNT(*) > 1
    ) multi_leaders;

    IF @multi_leader_count > 0
    BEGIN
        RAISERROR('Verification Failed: Found %d projects with MULTIPLE leaders.', 16, 1, @multi_leader_count);
    END

    PRINT 'Verification Successful: Exactly one LEADER per active project.';
    
    COMMIT TRANSACTION;
    PRINT '=== MIGRATION COMMITTED ===';
END TRY
BEGIN CATCH
    IF @@TRANCOUNT > 0
    BEGIN
        ROLLBACK TRANSACTION;
        PRINT '=== MIGRATION ROLLED BACK DUE TO ERROR ===';
    END

    DECLARE @ErrorMessage NVARCHAR(4000) = ERROR_MESSAGE();
    DECLARE @ErrorSeverity INT = ERROR_SEVERITY();
    DECLARE @ErrorState INT = ERROR_STATE();
    
    RAISERROR (@ErrorMessage, @ErrorSeverity, @ErrorState);
END CATCH
GO
