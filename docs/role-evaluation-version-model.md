# Role Evaluation Version Model

## Overview
The `RoleEvaluationVersion` model represents an immutable snapshot of an approved role evaluation. Unlike `RoleEvaluationDraft`, which is mutable and tracks ongoing work, the version model is strictly append-only and represents business records that cannot be changed once approved.

## Persistence
- **Storage**: MongoDB collection `role_evaluation_versions`
- **Immutability Enforcements**:
  - Unique compound index on `evaluationId` + `versionNumber`. Attempts to save a duplicate version will throw a `DuplicateKeyException`.
  - The repository interface (`RoleEvaluationVersionRepository`) exposes only `save` and `findById` / `findBy...` methods. Update and delete APIs are intentionally omitted.
  - Controllers expose read-only operations for approved versions.

## Structure
- **No Scoring Fields**: The model strictly excludes `overallScore`, `weights`, `normalizedScores`, and numeric calculation details. It stores the qualitative business inputs only.
- **Criteria**: Map of `CriterionSnapshot`, containing rationale, missing data notes, and typed evidence references.
- **Source References**: List of `ApprovedSourceReference` denoting the exact versions of documents/metrics that justify the evaluation.
- **Pointer Updates**: When a new version is approved, the new immutable document is persisted. The `currentApprovedVersionId` and `currentApprovedVersionNumber` pointers on the `RoleEvaluationDraft` are then updated. Old versions are never updated by this pointer reassignment.

## Period Selection
Metrics included in the evaluation must conform strictly to the evaluated `EvaluationPeriod`:
- `POINT_IN_TIME` metrics must match the `asOfDate` exactly.
- `PERIOD` metrics must be fully contained within the `periodStart` and `periodEnd` boundaries.
- Deduplication ensures only the highest approved version for a distinct `roleMetricRecordId` within the period is selected.

## Phase Constraints
*Note*: Phase 2C.5A establishes this foundation only. It does not implement the actual approval workflow, outbox pattern, or AI generation tasks.
