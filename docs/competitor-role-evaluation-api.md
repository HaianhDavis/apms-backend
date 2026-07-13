# Competitor Role Evaluation API

## Overview
This document describes the REST API for managing `RoleEvaluationDraft` resources for the COMPETITOR role.

## Endpoints

### Create Draft
`POST /api/v1/projects/{projectId}/tasks/{taskId}/role-evaluations`
- Creates a new draft.
- The `evaluatedRole` is derived directly from `Project.targetRelationshipType`. The client does not and cannot select the official role.
- At most one active draft per `(projectId, taskId, evaluatedRole)`.

### Get Draft
`GET /api/v1/role-evaluations/{evaluationId}`
- Returns the draft along with staleness flags (`staleTargetProfile`, `staleReferenceProfile`, `staleRuleSet`).

### Update Criterion
`PATCH /api/v1/role-evaluations/{evaluationId}/criteria/{criterionKey}`
- Allows updating manual scores and adding explanations for criteria.

### Add Evidence
`POST /api/v1/role-evaluations/{evaluationId}/evidence`
- Appends evidence to a specific criterion.

### Suggest Product-Market Overlap
`POST /api/v1/role-evaluations/{evaluationId}/product-market-overlap/suggest`
- Calculates and returns a proposed score based on Jaccard similarity.

### Suggest Criterion
`POST /api/v1/role-evaluations/{evaluationId}/criteria/{criterionKey}/suggest`
- Triggers AI suggestion generation for the specified criterion.

### Accept Suggestion
`POST /api/v1/role-evaluations/{evaluationId}/criteria/{criterionKey}/suggest/accept`
- Acceptance converts the suggestion into a CriterionInput.
- `inputMethod = AUTOMATIC_PROPOSAL`.
- Manager confirmation is still required before approval.
- The legacy product-market overlap endpoint `/product-market-overlap/accept` remains supported.

### Edit Suggestion
`POST /api/v1/role-evaluations/{evaluationId}/criteria/{criterionKey}/suggest/edit`
- Modifies the suggested score and saves it as `MANUAL_OVERRIDE`.
- `overrideReason` is required.

### Reject Suggestion
`POST /api/v1/role-evaluations/{evaluationId}/criteria/{criterionKey}/suggest/reject`
- Rejects the AI suggestion completely and removes any associated `AUTOMATIC_PROPOSAL` input.
- `reviewComment` is required.

### Request More Data for Suggestion
`POST /api/v1/role-evaluations/{evaluationId}/criteria/{criterionKey}/suggest/needs-more-data`
- Marks the suggestion as needing more data.
- The `missingData` list allows structured tracking of gaps preventing scoring.

### Calculate Preview
`POST /api/v1/role-evaluations/{evaluationId}/calculate-preview`
- Calculates a preview of the final score using the current inputs without saving a canonical snapshot.

### Submit
`POST /api/v1/role-evaluations/{evaluationId}/submit`
- Submits the draft for manager review.

### Review
`POST /api/v1/role-evaluations/{evaluationId}/review`
- Approves, rejects, or requests revision on a draft.
- Uses the `Idempotency-Key` header for safe retries during cross-database saving.

When Manager selects `REQUEST_REVISION`:
- `RoleEvaluationDraft.status` = `REVISION_REQUIRED`
- `ProjectTask.status` = `IN_PROGRESS`
- current `ProjectTaskSubmission.status` = `REJECTED`
- submission REJECTED means that specific submission was not accepted
- it does not mean the RoleEvaluationDraft is permanently rejected
- Staff may revise the same active draft
- Staff may create a new submission after revision

Permanent rejection is documented separately:
- `RoleEvaluationDraft.status` = `REJECTED`
- `RoleEvaluationDraft.active` = false
- `RoleEvaluationDraft.activeDraftKey` = null
- no `ScoreSnapshot` is created
