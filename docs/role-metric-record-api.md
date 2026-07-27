# Role Metric Record API

Base Path: `/api/v1/projects/{projectId}/role-metrics`

## Security
- Working details (DRAFT, SUBMITTED, REJECTED) are only accessible to `STAFF` and `MANAGER` roles.
- The `BUSINESS_OWNER` can only access approved historical snapshots or the current approved version.

## Endpoints

### `POST /`
Creates a new `RoleMetricRecord` in `DRAFT` state. Requires `metricKey`, target/actual values, and date/period settings based on metric definition.

### `PATCH /{metricId}`
Updates a draft record. Can modify period identity fields only if no approved version exists.

### `POST /{metricId}/evidences`
Attaches evidence to a metric record. Supports `CONTRACT_CLAUSE`, `RAW_DOCUMENT`, or `MANUAL_EXTERNAL`. Source uniqueness and project/task ownership is verified.

### `DELETE /{metricId}/evidences/{evidenceId}`
Removes evidence from a working draft.

### `POST /{metricId}/submit`
Submits a metric for review. Automatically validates that target/actual completeness rules and evidence requirements are satisfied.

### `POST /{metricId}/review`
Manager-only. Reviews a submission. If approved, atomically creates `RoleMetricRecordVersion` and associated evidence snapshots. Otherwise sets to `CHANGES_REQUESTED` or `REJECTED`.

### `POST /{metricId}/revisions`
Creates a new working draft from a finalized metric, incrementing `workingRevisionNumber` if previously approved or rejected.

### `GET /{metricId}/current-approved`
Returns the `RoleMetricRecordVersion` representing the active approved state.

### `GET /{metricId}/versions/{versionNumber}`
Retrieves a specific approved historical snapshot.
