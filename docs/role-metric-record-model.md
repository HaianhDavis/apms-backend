# Role Metric Record Model

The Role Metric Record subsystem represents factual input data for measuring partner performance over time.

## Core Aggregates

The core entity is the `RoleMetricRecord`. It represents a single fact or measurement for a company in a specific relationship context (e.g., `PARTNER_WITH`).

### Persistence Strategy
To support complex auditing, dispute resolution, and historical reporting, we use a working-draft/approved-snapshot model.

- **`RoleMetricRecord`**: The editable working aggregate. Managers and staff update this record in `DRAFT`, `SUBMITTED`, or `CHANGES_REQUESTED` state.
- **`RoleMetricRecordVersion`**: The immutable approved snapshot. Created atomically when a manager approves a submission.
- **`RoleMetricEvidence`**: Editable draft evidence attached to the working record.
- **`RoleMetricEvidenceVersion`**: Immutable snapshot of evidence created at approval time.

### Concurrency and Duplication Policy
- **POINT_IN_TIME**: Measurements happening at an exact `measurementDate`. Enforced unique by `periodKey = "AT:{date}"`.
- **PERIOD**: Measurements covering a `periodStart` to `periodEnd` inclusive range. `SERIALIZABLE` isolation is used during creation/updates to check for overlapping ranges and map constraint failures. Period exact matches are prevented by `periodKey = "PERIOD:{start}/{end}"`.

### Completeness
A metric cannot be submitted unless it contains at least a target or an actual value, and every provided value has matching evidence.
