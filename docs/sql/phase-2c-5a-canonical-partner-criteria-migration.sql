-- Pre-migration collision detection
-- STOP IF THIS RETURNS > 0
SELECT COUNT(*) 
FROM (
  SELECT company_id, project_id, task_id
  FROM role_metric_records
  WHERE metric_key IN ('capabilityComplementarityScore', 'capabilityAndComplementarityScore', 'governanceComplianceScore', 'governanceAndRiskScore')
  GROUP BY company_id, project_id, task_id
  HAVING 
    (SUM(CASE WHEN metric_key = 'capabilityComplementarityScore' THEN 1 ELSE 0 END) > 0 
     AND SUM(CASE WHEN metric_key = 'capabilityAndComplementarityScore' THEN 1 ELSE 0 END) > 0)
    OR
    (SUM(CASE WHEN metric_key = 'governanceComplianceScore' THEN 1 ELSE 0 END) > 0 
     AND SUM(CASE WHEN metric_key = 'governanceAndRiskScore' THEN 1 ELSE 0 END) > 0)
) as collisions;

BEGIN TRAN;

-- Idempotent rename for RoleMetricRecord
UPDATE role_metric_records
SET metric_key = 'capabilityAndComplementarityScore'
WHERE metric_key = 'capabilityComplementarityScore'
  AND NOT EXISTS (
      SELECT 1 FROM role_metric_records r2
      WHERE r2.metric_key = 'capabilityAndComplementarityScore'
        AND r2.company_id = role_metric_records.company_id
        AND r2.project_id = role_metric_records.project_id
        AND r2.task_id = role_metric_records.task_id
  );

UPDATE role_metric_records
SET metric_key = 'governanceAndRiskScore'
WHERE metric_key = 'governanceComplianceScore'
  AND NOT EXISTS (
      SELECT 1 FROM role_metric_records r2
      WHERE r2.metric_key = 'governanceAndRiskScore'
        AND r2.company_id = role_metric_records.company_id
        AND r2.project_id = role_metric_records.project_id
        AND r2.task_id = role_metric_records.task_id
  );

-- Idempotent rename for RoleMetricRecordVersion
UPDATE role_metric_record_versions
SET metric_key = 'capabilityAndComplementarityScore'
WHERE metric_key = 'capabilityComplementarityScore';

UPDATE role_metric_record_versions
SET metric_key = 'governanceAndRiskScore'
WHERE metric_key = 'governanceComplianceScore';

-- Idempotent rename for RoleCriterionRule
UPDATE role_criterion_rules
SET criterion_key = 'capabilityAndComplementarityScore'
WHERE criterion_key = 'capabilityComplementarityScore';

UPDATE role_criterion_rules
SET criterion_key = 'governanceAndRiskScore'
WHERE criterion_key = 'governanceComplianceScore';

COMMIT TRAN;

-- Post-migration legacy-key count
-- THIS MUST BE 0
SELECT COUNT(*) FROM role_metric_records 
WHERE metric_key IN ('capabilityComplementarityScore', 'governanceComplianceScore');

-- Post-migration duplicate check
-- THIS MUST BE 0
SELECT COUNT(*)
FROM role_metric_records
WHERE metric_key IN ('capabilityAndComplementarityScore', 'governanceAndRiskScore')
GROUP BY company_id, project_id, task_id, metric_key
HAVING COUNT(*) > 1;

-- Rollback instructions:
-- If collisions exist, address them manually before running the script.
-- If an error occurs mid-script, the transaction will rollback automatically.
-- To reverse changes, run the inverse UPDATE scripts if the transaction was committed.
