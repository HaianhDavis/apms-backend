# Task Type Workbench Audit

## 1. ProjectTask Fields
- **Current State:** `ProjectTask` currently has `id`, `project`, `assignedToAccount`, `title`, `description`, `status` (`TaskStatus`), `priority`, `dueDate`, `createdAt`, `updatedAt`, `completedAt`.
- **Finding:** It does **NOT** have any `taskType` or category field.

## 2. Existing Task APIs
- **Create:** `POST /api/v1/projects/{projectId}/tasks` (`ProjectTaskController.createTask`)
- **Update:** `PATCH /api/v1/projects/{projectId}/tasks/{taskId}` (`ProjectTaskController.updateTask`)
- **List:** `GET /api/v1/projects/{projectId}/tasks` (`ProjectTaskController.getTasks`)

## 3. Existing Document APIs (for DOCUMENT_COLLECTION)
- **Upload:** `POST /api/v1/projects/{projectId}/documents/upload`
- **Manual Input:** `POST /api/v1/projects/{projectId}/documents/manual`
- **List:** `GET /api/v1/projects/{projectId}/documents`
- **Visibility/Delete:** `PATCH .../visibility`, `DELETE .../{rawDocumentId}`
- **Finding:** These fully support the `DOCUMENT_COLLECTION` task actions.

## 4. Existing Extraction APIs (for COMPANY_DATA_PREPARATION)
- **Extract:** Extracted via `ImportJob` context (typically `/ai/extract` or `/import-jobs/{id}/ai-extractions`).
- **Draft Candidate:** `POST /api/v1/projects/{projectId}/tasks/{taskId}/candidates/from-extractions` (`CandidateMergeController`).
- **Draft Proposal:** `POST /api/v1/projects/{projectId}/tasks/{taskId}/profile-update-proposals/from-extractions`.
- **Finding:** These fully support generating drafts from AI extractions.

## 5. Existing Submission API
- **Submit:** `POST /api/v1/projects/{projectId}/tasks/{taskId}/submissions` (`ProjectTaskSubmissionController`).
- **List:** `GET .../submissions`
- **Review:** `POST .../{submissionId}/review`
- **Finding:** `ProjectTaskSubmissionService` handles submitting `CompanyCandidate` or `CompanyProfileUpdateProposal`. We just need to add the "One active submitted draft" rule to prevent submitting multiple drafts under review.

## 6. Minimal New APIs Needed
1. **Workbench Endpoint:** `GET /api/v1/projects/{projectId}/tasks/{taskId}/workbench` to aggregate task context, documents, generated drafts, submissions, and evaluate `availableActions`.
2. No other new API endpoints are required. Existing endpoints should be updated to accept and return `taskType`.

## 7. Entity Updates Needed
- **`ProjectTask`:** Add `TaskType taskType`.
- **`CompanyCandidate`:** Add `taskId` and `extractionIds` to map drafts to a specific task and extractions, similar to `CompanyProfileUpdateProposal`.
