DECLARE @ConstraintName nvarchar(200);

SELECT @ConstraintName = cc.name
FROM sys.check_constraints cc
JOIN sys.columns col ON cc.parent_object_id = col.object_id AND cc.parent_column_id = col.column_id
WHERE cc.parent_object_id = OBJECT_ID('dbo.projects')
  AND col.name = 'status';

-- Fallback if the constraint wasn't bound to the specific column but just the table
IF @ConstraintName IS NULL
BEGIN
    SELECT @ConstraintName = cc.name
    FROM sys.check_constraints cc
    WHERE cc.parent_object_id = OBJECT_ID('dbo.projects')
      AND cc.definition LIKE '%\[status\]%' ESCAPE '\';
END

IF @ConstraintName IS NOT NULL
BEGIN
    DECLARE @SQL nvarchar(1000) = 'ALTER TABLE dbo.projects DROP CONSTRAINT ' + QUOTENAME(@ConstraintName) + ';';
    EXEC sp_executesql @SQL;
END
GO

ALTER TABLE dbo.projects
ADD CONSTRAINT CK_projects_status CHECK (
    [status] = 'DRAFT' OR
    [status] = 'ACTIVE' OR
    [status] = 'COMPLETED' OR
    [status] = 'CLOSED' OR
    [status] = 'CANCELLED' OR
    [status] = 'ARCHIVED'
);
GO
