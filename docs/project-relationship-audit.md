# APMS Project Creation & Relationship Classification Audit

**Date:** 2026-07-06  
**Scope:** Project creation, ProjectType, priority field, relationship/classification source, candidate approval, CompanyProfile creation, Neo4j relationship creation.

---

## 1. Current Project Entity Fields

**File:** [`Project.java`](file:///Users/davisiukem/IdeaProjects/apms-backend/src/main/java/com/apms/domain/project/Project.java)  
**Table:** `projects` (SQL Server)

| Field | Type | Nullable | Notes |
|-------|------|----------|-------|
| `id` | Long | NO | Auto-increment PK |
| `projectName` | String | NO | — |
| `projectType` | `ProjectType` enum | NO | `UPDATE_EXISTING_COMPANY` or `RESEARCH_NEW_COMPANY` |
| `targetCompanyProfileId` | String | YES | Required only for `UPDATE_EXISTING_COMPANY` |
| `targetCompanyName` | String | NO | Always required |
| `description` | String (NVARCHAR MAX) | YES | — |
| `status` | `ProjectStatus` | NO | Default: `DRAFT` |
| `createdByAccount` | FK → Account | NO | — |
| `members` | OneToMany → `ProjectMember` | — | Cascade ALL |
| `createdAt` | LocalDateTime | — | Auto |
| `updatedAt` | LocalDateTime | — | Auto |

> ⚠️ **`targetRelationshipType` does NOT exist** on `Project` or any project DTO.  
> ⚠️ **`priority` does NOT exist** on `Project`. It exists only on `ProjectTask`.

---

## 2. Priority Field Audit

### Finding: Priority exists on `ProjectTask`, NOT on `Project`

| Component | Has `priority`? | Notes |
|-----------|-----------------|-------|
| `Project.java` | ❌ NO | — |
| `CreateProjectRequest.java` | ❌ NO | — |
| `UpdateProjectRequest.java` | ❌ NO | — |
| `ProjectResponse.java` | ❌ NO | — |
| `ProjectTask.java` | ✅ YES | `TaskPriority` enum field |
| `CreateProjectTaskRequest.java` | ✅ YES | Optional at task creation |
| `UpdateProjectTaskRequest.java` | ✅ YES | Only Manager can update it |
| `ProjectTaskResponse.java` | ✅ YES | Returned in task responses |

**`TaskPriority` enum values:** Inspect `common/enums/TaskPriority.java` for values (e.g. LOW, MEDIUM, HIGH, CRITICAL).

### Recommendation
- `priority` on `Project` does not exist in the backend. If the frontend mock includes it in a project creation form, it should be removed from the UI or mapped to a task-level field instead.
- No backend changes needed for project-level `priority`. It is already correctly scoped to tasks only.

---

## 3. Project Type Invariants

**File:** [`ProjectType.java`](file:///Users/davisiukem/IdeaProjects/apms-backend/src/main/java/com/apms/common/enums/ProjectType.java)

```java
public enum ProjectType {
    UPDATE_EXISTING_COMPANY,
    RESEARCH_NEW_COMPANY
}
```

> Note: The original code referenced `RESEARCH_MULTIPLE_COMPANIES` in comments inside `ProjectService`, but this value does **not** exist in the enum. The comment is stale.

### Validation in `ProjectService`

| ProjectType | `targetCompanyProfileId` | `targetCompanyName` |
|-------------|--------------------------|---------------------|
| `UPDATE_EXISTING_COMPANY` | **Required** | Required |
| `RESEARCH_NEW_COMPANY` | Must be **null** | Required |

---

## 4. Relationship Classification — Current End-to-End Flow

### Step 1: AI Extraction (per document)

**Files:**
- [`GeminiExtractionProvider.java`](file:///Users/davisiukem/IdeaProjects/apms-backend/src/main/java/com/apms/domain/ai/service/provider/GeminiExtractionProvider.java)
- [`MockExtractionProvider.java`](file:///Users/davisiukem/IdeaProjects/apms-backend/src/main/java/com/apms/domain/ai/service/provider/MockExtractionProvider.java)

The AI prompt currently **asks the model to infer `relationshipSuggestion`**:

```
- relationshipSuggestion (object with "suggestedType" string, "confidence" number, "reasoning" array)
Relationship "suggestedType" MUST be exactly one of:
  PARTNER_WITH | COMPETITOR_OF | SUPPLIER_OF | CUSTOMER_OF | POTENTIAL_PARTNER_OF
Infer the relationshipSuggestion only from available business context.
```

This means **the AI is currently the source of truth** for the initial relationship type suggestion.

---

### Step 2: Candidate Creation

**File:** [`CandidateService.java`](file:///Users/davisiukem/IdeaProjects/apms-backend/src/main/java/com/apms/domain/candidate/service/CandidateService.java) — `buildAndSaveCandidate()`

The AI's `RelationshipSuggestion` is mapped directly into `CompanyCandidate`:

```java
candidate.suggestedRelationshipType = extSug.getSuggestedType()   // from AI
candidate.relationshipConfidenceScore = extSug.getConfidence()     // from AI
candidate.relationshipSuggestion = { suggestedType, confidence, reasoning }  // from AI
```

**`CompanyCandidate` fields related to relationship:**

| Field | Source | Notes |
|-------|--------|-------|
| `suggestedRelationshipType` | AI extraction | AI-generated |
| `relationshipConfidenceScore` | AI extraction | AI-generated |
| `relationshipSuggestion` | AI extraction | Full suggestion object with reasoning |
| `relationshipTypeOverride` | Manager at approval | Optional override, null by default |

---

### Step 3: Candidate Approval

**File:** [`CandidateService.approveCandidate()`](file:///Users/davisiukem/IdeaProjects/apms-backend/src/main/java/com/apms/domain/candidate/service/CandidateService.java#L264-L302)  
**Request:** [`ApproveCandidateRequest.java`](file:///Users/davisiukem/IdeaProjects/apms-backend/src/main/java/com/apms/domain/candidate/dto/ApproveCandidateRequest.java)

```java
// ApproveCandidateRequest
private RelationshipType relationshipTypeOverride;   // OPTIONAL — if null, AI suggestion is used
```

**Final type selection logic:**

```java
RelationshipType finalType = candidate.getRelationshipTypeOverride() != null
    ? candidate.getRelationshipTypeOverride()   // Manager-supplied override
    : candidate.getSuggestedRelationshipType(); // AI fallback ← PROBLEM
```

> ⚠️ **Critical Issue:** If the Manager does not explicitly provide `relationshipTypeOverride`, the system falls back to the **AI-suggested type**. The AI type becomes the `finalRelationshipType` used for Neo4j graph creation.

---

### Step 4: Event Publication

**File:** [`CandidateApprovedEvent.java`](file:///Users/davisiukem/IdeaProjects/apms-backend/src/main/java/com/apms/common/event/CandidateApprovedEvent.java)

```java
eventPublisher.publishEvent(new CandidateApprovedEvent(
    candidateId,
    candidate.getProjectId(),
    finalType,           // ← AI suggestion or Manager override
    confidence
));
```

---

### Step 5: Neo4j Graph Relationship Creation

**File:** [`GraphService.java`](file:///Users/davisiukem/IdeaProjects/apms-backend/src/main/java/com/apms/domain/graph/service/GraphService.java)

- Listens for `CandidateApprovedEvent`.
- Receives `finalRelationshipType` from the event.
- Creates the Neo4j relationship:

```cypher
MERGE (c1)-[r:PARTNER_WITH]->(c2)  -- relationship type injected dynamically
SET r.confidenceScore = ...
    r.confirmedBy = ...
    r.projectId = ...
    r.candidateId = ...
```

**Condition for relationship creation:**
```java
if (StringUtils.hasText(targetCompanyProfileId) && finalRelType != null) {
    // create relationship
}
```

> If `targetCompanyProfileId` is null (i.e., `RESEARCH_NEW_COMPANY` project), **no relationship is created in Neo4j** — only the company node is merged.

---

## 5. Current API Endpoints for Project & Relationship Flow

| Method | Endpoint | Role | Notes |
|--------|----------|------|-------|
| `POST` | `/api/v1/projects` | BUSINESS_DEVELOPMENT_MANAGER | Creates project. No `targetRelationshipType` field. |
| `PUT` | `/api/v1/projects/{id}` | BUSINESS_DEVELOPMENT_MANAGER | Updates name, description, status only. |
| `POST` | `/api/v1/import-jobs/{id}/ai-extractions` | BUSINESS_DEVELOPMENT_STAFF | Runs AI extraction — includes relationship suggestion. |
| `POST` | `/api/v1/ai-extractions/{id}/candidate` | BUSINESS_DEVELOPMENT_STAFF | Creates CompanyCandidate from extraction — copies AI suggestion. |
| `POST` | `/api/v1/projects/{pid}/tasks/{tid}/candidates/from-extractions` | STAFF/MANAGER | Multi-doc merge → DRAFT candidate. |
| `POST` | `/api/v1/candidates/{id}/approve` | BUSINESS_DEVELOPMENT_MANAGER | Approves candidate; optional `relationshipTypeOverride`. |
| `GET` | `/api/v1/graph/companies/{companyId}` | BUSINESS_OWNER, MANAGER | Reads Neo4j graph node + relationships. |

---

## 6. Places That Need Code Changes

### A. `Project.java` — Add `targetRelationshipType` field

The Manager should declare the intended relationship type **when creating the project**. This eliminates AI as the source of truth.

**Recommended new field:**
```java
@Enumerated(EnumType.STRING)
@Column(nullable = true)  // nullable for RESEARCH_NEW_COMPANY projects
private RelationshipType targetRelationshipType;
```

**Recommended field name:** `targetRelationshipType`

Rationale: Consistent with existing naming (`targetCompanyProfileId`, `targetCompanyName`). Clearly communicates intent.

---

### B. `CreateProjectRequest.java` — Add `targetRelationshipType`

```java
@NotNull(message = "targetRelationshipType is required for UPDATE_EXISTING_COMPANY")
private RelationshipType targetRelationshipType;
```

> Validation rule: Required for `UPDATE_EXISTING_COMPANY`, optional/null for `RESEARCH_NEW_COMPANY`.

---

### C. `UpdateProjectRequest.java` — Add `targetRelationshipType` (optional)

Allow updating the relationship type before the project is completed.

---

### D. `ProjectResponse.java` — Add `targetRelationshipType`

Return the manager-selected type in all project read endpoints.

---

### E. `ProjectService.createProject()` — Validate and persist `targetRelationshipType`

Add to `validateProjectTypeInvariants()`:
- `UPDATE_EXISTING_COMPANY` → `targetRelationshipType` must not be null.
- `RESEARCH_NEW_COMPANY` → `targetRelationshipType` is optional (may be null initially).

---

### F. `CandidateService.approveCandidate()` — Use Project's relationship type as default

Instead of falling back to the AI suggestion, fall back to `project.getTargetRelationshipType()`:

```java
// Current (WRONG — AI is source of truth):
RelationshipType finalType = candidate.getRelationshipTypeOverride() != null
    ? candidate.getRelationshipTypeOverride()
    : candidate.getSuggestedRelationshipType();   // AI fallback

// New (Manager is source of truth):
RelationshipType finalType = candidate.getRelationshipTypeOverride() != null
    ? candidate.getRelationshipTypeOverride()     // explicit approval-time override
    : project.getTargetRelationshipType();        // project-level manager selection
// AI suggestion kept for informational purposes only, not used for graph
```

This requires `CandidateService` to load the project from `ProjectRepository`.

---

### G. `GeminiExtractionProvider` and `OpenAiExtractionProvider` — Remove `relationshipSuggestion` from prompt

The AI prompt currently instructs the model to **infer** the relationship type. This should be removed so AI only extracts factual business data.

**Remove from prompt:**
```
- relationshipSuggestion (object with "suggestedType"...)
Relationship "suggestedType" MUST be exactly one of: ...
Infer the relationshipSuggestion only from available business context.
```

> `RelationshipSuggestion` can be kept as a deprecated display-only field on `CompanyCandidate` but should no longer drive business logic.

---

### H. `ApproveCandidateRequest.java` — Keep `relationshipTypeOverride` as optional refinement

The Manager can still override the project-level relationship type at approval time if needed. This is acceptable.

---

## 7. Recommended Implementation Plan

### Phase 1 — Add `targetRelationshipType` to Project (blocking change)
1. Add `targetRelationshipType RelationshipType` column to `projects` table (nullable).
2. Add field to `Project.java`, `CreateProjectRequest`, `UpdateProjectRequest`, `ProjectResponse`.
3. Add validation: required for `UPDATE_EXISTING_COMPANY`.
4. Update `ProjectService` to persist and return the field.

### Phase 2 — Use `targetRelationshipType` in candidate approval
1. Inject `ProjectRepository` into `CandidateService`.
2. Load project in `approveCandidate()`.
3. Use `project.getTargetRelationshipType()` as the fallback (replacing AI suggestion fallback).
4. Keep `relationshipTypeOverride` in `ApproveCandidateRequest` as optional approval-time correction.

### Phase 3 — Remove relationship inference from AI prompts
1. Update `GeminiExtractionProvider` system prompt — remove `relationshipSuggestion` instruction.
2. Update `OpenAiExtractionProvider` similarly.
3. Keep `RelationshipSuggestion` field in `ExtractedCompanyData` and `CompanyCandidate` as nullable/ignored.
4. `MockExtractionProvider` can keep its mock data for test stability (or set to null).

---

## 8. Risk Notes

| Risk | Severity | Mitigation |
|------|----------|------------|
| Existing candidates have `suggestedRelationshipType` from AI | Low | Field is kept; just no longer used in graph creation |
| `RESEARCH_NEW_COMPANY` projects may have no `targetRelationshipType` | Low | Field is nullable; graph relationship is skipped if `targetCompanyProfileId` is null (already the behavior) |
| Schema migration needed to add column | Medium | Add nullable column with no default — existing rows will have null; enforce via validation only for new projects |
| `CandidateService` loading `Project` across SQL boundary | Low | `ProjectRepository` already used by `GraphService`; straightforward to inject |
| AI prompt change may alter extraction quality | Low | Removing one field from the prompt schema is a minor change; factual fields are unaffected |

---

## 9. Backward Compatibility Notes

- **`suggestedRelationshipType`** and **`relationshipSuggestion`** on `CompanyCandidate` can remain — just demoted to informational display.
- **`ApproveCandidateRequest.relationshipTypeOverride`** remains optional — no breaking change to the approval API.
- **Existing approved candidates** in MongoDB retain their `suggestedRelationshipType` for history but no new Neo4j relationship will be incorrectly sourced from AI.
- **`Project` table migration**: Add a nullable `target_relationship_type VARCHAR(50)` column — no data loss, existing projects get null.
- **No changes** to `CompanyProfile`, versioning apply, `CompanyProfileUpdateProposal`, task submission, notifications, external data, or reports.

---

## 10. Summary of Findings

| Question | Answer |
|----------|--------|
| Does `priority` exist on `Project`? | ❌ No — it exists only on `ProjectTask` |
| Does `targetRelationshipType` exist on `Project`? | ❌ No — missing entirely |
| Where is relationship type currently decided? | **AI extraction** (Gemini/OpenAI prompt) → `suggestedRelationshipType` on candidate; Manager override is optional |
| Is AI the source of truth for relationship? | ✅ Yes — currently yes, via `suggestedRelationshipType` fallback at approval |
| Where is Neo4j relationship created? | `GraphService` on `CandidateApprovedEvent` |
| Recommended new field name | `targetRelationshipType` on `Project` |
| Schema change needed? | ✅ Yes — add nullable column `target_relationship_type` to `projects` table |
