# Phase 2C.5A Migration Artifacts

**Objective**: Safely migrate legacy PARTNER evaluation criteria keys to canonical ones.

Legacy Mappings:
- `capabilityComplementarityScore` -> `capabilityAndComplementarityScore`
- `governanceComplianceScore` -> `governanceAndRiskScore`

## 1. Pre-Migration Collision Checks (SQL & Mongo)

These checks must return ZERO rows/documents before executing the actual migrations.

### SQL (RoleMetricRecord)
```sql
SELECT company_id, project_id, task_id
FROM role_metric_records
WHERE metric_key IN ('capabilityComplementarityScore', 'capabilityAndComplementarityScore', 'governanceComplianceScore', 'governanceAndRiskScore')
GROUP BY company_id, project_id, task_id
HAVING 
  (SUM(CASE WHEN metric_key = 'capabilityComplementarityScore' THEN 1 ELSE 0 END) > 0 
   AND SUM(CASE WHEN metric_key = 'capabilityAndComplementarityScore' THEN 1 ELSE 0 END) > 0)
  OR
  (SUM(CASE WHEN metric_key = 'governanceComplianceScore' THEN 1 ELSE 0 END) > 0 
   AND SUM(CASE WHEN metric_key = 'governanceAndRiskScore' THEN 1 ELSE 0 END) > 0);
```

### Mongo (RoleEvaluationDrafts)
```javascript
// Run in mongosh to detect drafts that somehow contain both the legacy and new key
db.role_evaluation_drafts.find({
  $or: [
    { 
      "criterionInputs.capabilityComplementarityScore": { $exists: true },
      "criterionInputs.capabilityAndComplementarityScore": { $exists: true }
    },
    { 
      "criterionInputs.governanceComplianceScore": { $exists: true },
      "criterionInputs.governanceAndRiskScore": { $exists: true }
    }
  ]
})
```

*Note: If any collisions are found, the typed `BusinessMigrationConflictException` must be addressed manually. Do not merge silently.*

## 2. Idempotent SQL Migration

```sql
BEGIN TRAN;

-- 1. Migrate RoleMetricRecord
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

-- 2. Migrate RoleMetricRecordVersion
UPDATE role_metric_record_versions
SET metric_key = 'capabilityAndComplementarityScore'
WHERE metric_key = 'capabilityComplementarityScore';

UPDATE role_metric_record_versions
SET metric_key = 'governanceAndRiskScore'
WHERE metric_key = 'governanceComplianceScore';

-- 3. Migrate RoleCriterionRule (if seeded)
UPDATE role_criterion_rules
SET criterion_key = 'capabilityAndComplementarityScore'
WHERE criterion_key = 'capabilityComplementarityScore';

UPDATE role_criterion_rules
SET criterion_key = 'governanceAndRiskScore'
WHERE criterion_key = 'governanceComplianceScore';

COMMIT TRAN;
```

## 3. Idempotent Mongo Migration

```javascript
// Migrate capabilityComplementarityScore
db.role_evaluation_drafts.updateMany(
  { "criterionInputs.capabilityComplementarityScore": { $exists: true } },
  [
    { $set: { "criterionInputs.capabilityAndComplementarityScore": "$criterionInputs.capabilityComplementarityScore" } },
    { $unset: ["criterionInputs.capabilityComplementarityScore"] }
  ]
);

db.role_evaluation_drafts.updateMany(
  { "automaticSuggestions.capabilityComplementarityScore": { $exists: true } },
  [
    { $set: { "automaticSuggestions.capabilityAndComplementarityScore": "$automaticSuggestions.capabilityComplementarityScore" } },
    { $unset: ["automaticSuggestions.capabilityComplementarityScore"] }
  ]
);

db.role_evaluation_drafts.updateMany(
  { "criterionEvidence.capabilityComplementarityScore": { $exists: true } },
  [
    { $set: { "criterionEvidence.capabilityAndComplementarityScore": "$criterionEvidence.capabilityComplementarityScore" } },
    { $unset: ["criterionEvidence.capabilityComplementarityScore"] }
  ]
);

// Migrate governanceComplianceScore
db.role_evaluation_drafts.updateMany(
  { "criterionInputs.governanceComplianceScore": { $exists: true } },
  [
    { $set: { "criterionInputs.governanceAndRiskScore": "$criterionInputs.governanceComplianceScore" } },
    { $unset: ["criterionInputs.governanceComplianceScore"] }
  ]
);

db.role_evaluation_drafts.updateMany(
  { "automaticSuggestions.governanceComplianceScore": { $exists: true } },
  [
    { $set: { "automaticSuggestions.governanceAndRiskScore": "$automaticSuggestions.governanceComplianceScore" } },
    { $unset: ["automaticSuggestions.governanceComplianceScore"] }
  ]
);

db.role_evaluation_drafts.updateMany(
  { "criterionEvidence.governanceComplianceScore": { $exists: true } },
  [
    { $set: { "criterionEvidence.governanceAndRiskScore": "$criterionEvidence.governanceComplianceScore" } },
    { $unset: ["criterionEvidence.governanceComplianceScore"] }
  ]
);
```

## 4. Post-Migration Verification Queries

### SQL Verification
```sql
-- Ensure 0 legacy records remain
SELECT COUNT(*) FROM role_metric_records 
WHERE metric_key IN ('capabilityComplementarityScore', 'governanceComplianceScore');
```

### Mongo Verification
```javascript
// Ensure 0 legacy draft fields remain
db.role_evaluation_drafts.countDocuments({
  $or: [
    { "criterionInputs.capabilityComplementarityScore": { $exists: true } },
    { "criterionInputs.governanceComplianceScore": { $exists: true } }
  ]
});
```

## 5. Rollback and Recovery Documentation

If an unexpected `BusinessMigrationConflictException` occurs mid-migration or the data is found to be in an inconsistent state:
1. Stop the application.
2. For SQL: Run the inverse `UPDATE` statements if the transaction was committed.
3. For Mongo: Run the inverse update pipeline:
   ```javascript
   { $set: { "criterionInputs.capabilityComplementarityScore": "$criterionInputs.capabilityAndComplementarityScore" } },
   { $unset: ["criterionInputs.capabilityAndComplementarityScore"] }
   ```
4. Address the conflicting manual records directly via business operation interventions before re-attempting the migration.
