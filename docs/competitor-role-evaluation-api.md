# Competitor Role Evaluation API

## Overview
This API governs the lifecycle of `RoleEvaluationDraft` entities specifically for the COMPETITOR role. It orchestrates drafting, AI-assisted suggestion generation, criterion review (accept/edit/reject), and final submission/approval.

## Exact API Routes

### Drafting
- **POST** `/api/v1/projects/{projectId}/tasks/{taskId}/role-evaluations` - Creates a new draft.
- **GET** `/api/v1/role-evaluations/{evaluationId}` - Retrieves a draft.

### Criterion Data & Evidence
- **PATCH** `/api/v1/role-evaluations/{evaluationId}/criteria/{criterionKey}` - Manually updates a criterion score/note.
- **POST** `/api/v1/role-evaluations/{evaluationId}/evidence` - Adds custom draft evidence.

### Suggestion Generation
- **POST** `/api/v1/role-evaluations/{evaluationId}/suggestions/generate`
  - **Description**: Batch processes all six criteria.
  - **Payload**: `GenerateSuggestionRequest` (optional).
  - **Response**: `BatchGenerationResponse` mapping criterion keys to outcomes (`GENERATED`, `TECHNICAL_FAILURE`, `NEEDS_MORE_DATA`, `PROTECTED_FROM_OVERWRITE`).
- **POST** `/api/v1/role-evaluations/{evaluationId}/criteria/{criterionKey}/suggest`
  - **Description**: Generates a suggestion for a single criterion.
  - **Payload**: `GenerateSuggestionRequest` (optional).
  - **Response**: `SingleGenerationResponse`.
- **POST** `/api/v1/role-evaluations/{evaluationId}/product-market-overlap/suggest`
  - **Description**: Legacy route exclusively for the deterministic overlap calculation.

### Suggestion Review
- **POST** `/api/v1/role-evaluations/{evaluationId}/product-market-overlap/accept` - Accepts overlap.
- **POST** `/api/v1/role-evaluations/{evaluationId}/criteria/{criterionKey}/suggest/accept` - Accepts an AI suggestion.
- **POST** `/api/v1/role-evaluations/{evaluationId}/criteria/{criterionKey}/suggest/edit` - Edits an AI suggestion.
- **POST** `/api/v1/role-evaluations/{evaluationId}/criteria/{criterionKey}/suggest/reject` - Rejects an AI suggestion.
- **POST** `/api/v1/role-evaluations/{evaluationId}/criteria/{criterionKey}/suggest/needs-more-data` - Marks a suggestion as needing more data.

### Finalization
- **POST** `/api/v1/role-evaluations/{evaluationId}/calculate-preview` - Calculates preview scores.
- **POST** `/api/v1/role-evaluations/{evaluationId}/submit` - Submits draft for Manager review.
- **POST** `/api/v1/role-evaluations/{evaluationId}/review` - Manager approval/rejection.

## Authorization
- Drafting, Suggestion Generation, Review, and Submission are restricted to `ADMIN` or `STAFF`.
- Final Draft Review (Approval/Rejection) requires `ADMIN` or `MANAGER`.

## Structural Invariants
- There is **no** public endpoint for direct snapshot creation.
- There is **no** endpoint for manually inputting metric weights.
- There is **no** AI-generated `overallScore` exposed by the generation routes.
