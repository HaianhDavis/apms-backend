# Role Evaluation Draft Workflow

## Overview
This document describes the lifecycle of a `RoleEvaluationDraft` during APMS Phase 2B.

## State Machine
The draft transitions through several states managed by `RoleEvaluationStatus`:
1. **DRAFT**: The initial state. Staff can update criteria, add evidence, and request automatic proposals.
2. **IN_REVIEW**: The draft has been submitted. A manager reviews the evaluation.
3. **REVISION_REQUIRED**: The manager has requested changes. The draft is returned to the staff for further edits.
4. **REJECTED**: The manager has permanently rejected the draft, terminating this specific evaluation. The active draft lock (`activeDraftKey`) is cleared.
5. **APPROVAL_PROCESSING**: The manager has approved the draft. The system is calculating final scores and persisting the canonical snapshot across database boundaries.
6. **APPROVED**: Processing is complete, and the final canonical `ScoreSnapshot` is available. The active draft lock is cleared.
7. **APPROVAL_FAILED**: An error occurred during cross-database saving (e.g., SQL server unavailability). The draft remains active and can be retried.

## Active Draft Lock
To ensure at most one active draft per `projectId + taskId + evaluatedRole`, the field `activeDraftKey` holds a composite key in the format `{projectId}:{taskId}:{evaluatedRole}`. A unique sparse Mongo index on this field (`@Indexed(unique = true, sparse = true)`) enforces this constraint:
- **Active states** (DRAFT, IN_REVIEW, REVISION_REQUIRED, APPROVAL_FAILED): `activeDraftKey` is set; `active = true`.
- **Terminal states** (APPROVED, REJECTED): `activeDraftKey` is cleared to `null`; `active = false`.
- Because the index is **sparse**, multiple documents with `activeDraftKey = null` (terminal states) are all allowed.

## Revision Workflow — Submission Status Mapping
When a manager requests revision, the following state transitions occur:

| Entity | Field | Old Value | New Value | Notes |
|--------|-------|-----------|-----------|-------|
| `RoleEvaluationDraft` | `status` | `IN_REVIEW` | `REVISION_REQUIRED` | **Draft status carries the revision meaning.** |
| `ProjectTaskSubmission` | `status` | `IN_REVIEW` | `REJECTED` | **Submission REJECTED means this particular submission cycle was not accepted.** It does NOT mean a permanent business rejection. |
| `ProjectTask` | `status` | `IN_REVIEW` | `IN_PROGRESS` | Task returns to active work so staff can revise the draft. |

**Important**: `SubmissionStatus.REJECTED` in the revision path is a technical state meaning "this submission cycle was not accepted." It is distinct from the permanent business rejection (`RoleEvaluationStatus.REJECTED`). After revision, the staff may update criteria and create a new `ProjectTaskSubmission` with `SubmissionType.ROLE_EVALUATION`.

## Cross-Database Approval
Approval involves both MongoDB (Draft) and SQL Server (ScoreSnapshot). To ensure consistency without a distributed ACID transaction:

1. The Mongo draft transitions to `APPROVAL_PROCESSING` and stores the `approvalIdempotencyKey`.
2. The system checks SQL for an existing `ScoreSnapshot` via `findBySourceEvaluationDraftId(draftId)`.
   - If found: the SQL boundary was already crossed. Repair Mongo state → APPROVED. Return existing snapshot ID.
   - If not found: proceed to create a new snapshot.
3. A `ScoreSnapshot` is created in SQL Server using `CanonicalScoreSnapshotService.createCanonicalSnapshot(...)`.
4. If SQL succeeds, the Mongo draft is updated to APPROVED, `active = false`, `activeDraftKey = null`.
5. If SQL fails, the draft transitions to `APPROVAL_FAILED`. On retry, step 2 will find the existing snapshot and repair.

**This design guarantees exactly one `ScoreSnapshot` per approved draft**, using the SQL uniqueness index (`ux_score_snapshots_source_evaluation_draft`) as the final safeguard. The cross-database boundary is not a single ACID transaction.
