# Phase 2C Implementation Plan

## Purpose

This plan divides the work identified in the Phase 2C.0 data-model audit into small, safe implementation phases. Each phase is designed to be independently deployable and backward-compatible.

---

## Phase 2C.1 — Correct CompanyProfile Semantic Boundaries

### Goal
Clearly document and label the semantic boundary between factual company data and AI-generated insights within CompanyProfile, without destructive migration.

### Files likely affected
- `CompanyProfile.java` — Add clarifying Javadoc comments only
- `CompanyCandidate.java` — Add clarifying Javadoc comments only
- `ExtractedCompanyData.java` — Add clarifying Javadoc comments only

### Database changes
None.

### API changes
None.

### Migration risks
None — documentation-only changes.

### Backward-compatibility strategy
No behavioral changes. Existing `insights` SWOT field remains in CompanyProfile. A `@deprecated` annotation or Javadoc note may be added to signal future movement, but the field itself is not removed.

### Required tests
- Existing tests must continue to pass unchanged.
- No new tests required.

### Explicit non-goals
- Do NOT remove the `insights` field from CompanyProfile.
- Do NOT remove `scorePreview` from CompanyCandidate.
- Do NOT create a separate `AiInsight` entity in this phase.
- Do NOT change any public API response shapes.

---

## Phase 2C.2 — Establish Relationship Source of Truth

### Goal
Establish distinct relationship source-of-truth semantics:
1. **Project scope authority**: `Project.targetRelationshipType` (SQL) remains authoritative for the evaluation scope.
2. **Confirmed organizational relationship state**: Formal confirmation of an enterprise relationship must be explicit (do NOT build new workflows around the legacy `CandidateApprovedEvent`).
3. **Graph projection**: Neo4j serves as the confirmed relationship graph/projection.
4. **Contract lifecycle authority**: To be handled by `PartnerContract` (SQL), not forced into Neo4j edges.

This phase adds optional structural metadata properties (startDate, endDate, status) to the Neo4j edge where they naturally assist graph traversal, without implying that Neo4j is the sole authoritative store for all relationship lifecycle data.

### Files likely affected
- `GraphService.java` — Extend `createRelationship()` to accept optional metadata
- `CompanyRelationshipDto.java` — Add new fields (startDate, endDate, status)
- `CompanyNodeRepository.java` — No changes expected
- New: Cypher queries for relationship metadata updates

### Database changes
- Neo4j: Add optional properties to relationship edges (no schema migration needed — Neo4j is schemaless).

### API changes
- Extend existing graph API responses to include relationship metadata.
- No breaking changes to existing endpoints.

### Migration risks
Low — Neo4j properties are optional. Existing edges without new properties will return null for new fields.

### Backward-compatibility strategy
All new fields are optional. Existing API consumers receive additional fields but no removed fields.

### Required tests
- GraphService tests for relationship metadata CRUD
- API response tests for new optional fields

### Explicit non-goals
- Do NOT create a `CompanyRelationship` SQL entity.
- Do NOT duplicate Neo4j relationship data in MongoDB or SQL.
- Do NOT change `Project.targetRelationshipType` semantics.

---

## Phase 2C.3 — PartnerContract Design and Persistence

### Goal
Create a `PartnerContract` SQL Server entity to govern contract lifecycle authority. Enable contract document upload and metadata extraction.

**Linkage Rules:**
`PartnerContract` does NOT require Neo4j to be the sole source of truth. It links by:
- `referenceCompanyId`
- `partnerCompanyId`
- `relationshipType`
- optional `projectId`
- optional Neo4j relationship reference if needed

### Files likely affected
- New: `PartnerContract.java` (SQL Server entity)
- New: `PartnerContractRepository.java` (Spring Data JPA)
- New: `PartnerContractService.java`
- New: `PartnerContractController.java`
- New: Contract DTOs (request/response)
- `RawDocument.java` — May need a `documentType` or `contractId` link field
- `MongoConfig.java` — Add new repository package if needed

### Database changes
- SQL Server: New `partner_contracts` table with constraints and project/company relations.
- MongoDB: Only if needed for flexible extraction data.

### API changes
- New CRUD endpoints under `/api/v1/contracts`
- New document-contract linking endpoint

### Migration risks
Low — new entity with no dependency on existing data.

### Backward-compatibility strategy
Additive only. No existing entities modified.

### Required tests
- CRUD service tests
- Controller routing tests
- Document-contract linkage tests

### Explicit non-goals
- Do NOT embed contract data in CompanyProfile.
- Do NOT implement contract KPI tracking (deferred to 2C.4).
- Do NOT implement AI contract extraction (deferred).

---

## Phase 2C.4 — Role-Specific Metric Persistence

### Goal
Create a generic `RoleMetricRecord` MongoDB entity for storing role-specific operational metrics (KPIs, SLAs, delivery metrics, etc.) with evidence and review workflow.

### Files likely affected
- New: `RoleMetricRecord.java` (MongoDB document)
- New: `MetricEvidence.java` (embedded document)
- New: `RoleMetricRecordRepository.java`
- New: `RoleMetricService.java`
- New: `RoleMetricController.java`
- New: Metric DTOs

### Database changes
- MongoDB: New `role_metric_records` collection

### API changes
- New CRUD endpoints for role metrics
- New metric evidence attachment endpoint

### Migration risks
Low — new entity.

### Backward-compatibility strategy
Additive only. Scoring services optionally consume metrics when available.

### Required tests
- CRUD service tests per role type
- Evidence attachment tests
- Validation tests (role-specific field requirements)

### Explicit non-goals
- Do NOT create separate SQL tables per role type.
- Do NOT implement automatic metric aggregation formulas.
- Do NOT implement sub-metric weights.

---

## Phase 2C.5 — Scoring-Specific AI Suggestion Quality Model [x] COMPLETED

### Goal
Design and implement an AI criterion suggestion quality model with evidence, confidence, validation, and review status — separate from the extraction quality system.

### Files likely affected
- `AutomaticSuggestion.java` — Extend with quality fields (confidence, validationStatus, reviewStatus, missingData, method, promptVersion, modelProvider, modelVersion)
- New: `AiCriterionSuggestionStatus.java` enum
- `RoleEvaluationDraft.java` — May need suggestion-level review tracking
- `RoleEvaluationDraftService.java` — Suggestion quality validation logic

### Database changes
- MongoDB: Additional fields in `role_evaluation_drafts` embedded `AutomaticSuggestion`

### API changes
- Extended suggestion response with quality metadata
- New suggestion review endpoints (accept/edit/reject with quality context)

### Migration risks
Medium — extends existing `AutomaticSuggestion` embedded document. Existing drafts will have null for new fields.

### Backward-compatibility strategy
All new fields default to null. Existing suggestions remain valid. Migration script not required — MongoDB handles missing fields gracefully.

### Required tests
- Suggestion quality validation tests
- Quality field serialization tests
- Review workflow tests with quality context

### Explicit non-goals
- Do NOT reuse `ExtractionReviewStatus` or `ExtractionValidationStatus`.
- Do NOT store suggestions in `AiExtractionCache`.
- Do NOT implement actual AI calls in this phase.

---

## Phase 2C.6 — COMPETITOR Six-Criterion Suggestions

### Goal
Implement AI-assisted suggestion generation for the five remaining COMPETITOR criteria (marketPositionScore, competitiveCapabilityScore, strategicIntentScore, growthMomentumScore, competitiveThreatScore).

### Files likely affected
- New: `CompetitorCriterionSuggestionService.java`
- New: AI prompt templates for competitor criteria
- `CompetitorComparisonService.java` — Refactor into a component of the broader suggestion service
- `RoleEvaluationDraftService.java` — Integration with multi-criterion suggestion generation
- `ExternalDataItem.java` / `ExternalDataService.java` — Integration for signal-based criteria

### Database changes
None — uses existing `AutomaticSuggestion` in `RoleEvaluationDraft`.

### API changes
- New endpoint: `POST /api/v1/role-evaluations/{id}/criteria/{criterionKey}/suggest`
- Response includes quality metadata from Phase 2C.5

### Migration risks
Medium — introduces AI calls and external data integration.

### Backward-compatibility strategy
Suggestions are optional. Manual-only workflow remains functional. AI suggestions do not modify confirmed scores.

### Required tests
- Unit tests with mocked AI responses per criterion
- Evidence aggregation tests
- Quality validation tests per criterion
- Missing-data behavior tests (null scores, coverage warnings)

### Explicit non-goals
- Do NOT implement AI-generated official scores.
- Do NOT implement sub-metric weights.
- Do NOT let AI fabricate missing data.
- Do NOT let AI define AHP weights.
- Do NOT modify `competitiveThreatScore` derivation from other criteria.

---

## Phase 2C.7 — PARTNER Relationship Data and Scoring

### Goal
Enable the PARTNER evaluation workflow with relationship-specific data (contracts, KPIs, SLAs) and AI-assisted criterion suggestions for the six PARTNER criteria.

### Files likely affected
- All entities from Phases 2C.3 and 2C.4
- New: `PartnerCriterionSuggestionService.java`
- New: AI prompt templates for partner criteria
- `RoleEvaluationDraftService.java` — Integration with PARTNER-specific suggestion logic
- Contract-metric integration services

### Database changes
None beyond what was created in Phases 2C.3 and 2C.4.

### API changes
- New PARTNER-specific suggestion endpoints
- Integration with contract and metric data sources

### Migration risks
Medium — depends on successful completion of Phases 2C.3–2C.5.

### Backward-compatibility strategy
PARTNER manual-only workflow continues to work. AI suggestions are additive.

### Required tests
- Unit tests per PARTNER criterion with mocked AI responses
- Contract-metric data aggregation tests
- Missing relationship data behavior tests

### Explicit non-goals
- Do NOT implement sub-metric weights (EXPERT_INPUT_REQUIRED).
- Do NOT implement CUSTOMER, SUPPLIER, or POTENTIAL_PARTNER scoring.
- Do NOT modify AHP top-level weights.

---

## Phase Dependency Graph

```
Phase 2C.1 (CompanyProfile semantics) ─── standalone
Phase 2C.2 (Relationship source of truth) ─── standalone
Phase 2C.3 (PartnerContract) ─── depends on 2C.2
Phase 2C.4 (RoleMetricRecord) ─── depends on 2C.2
Phase 2C.5 (AI suggestion quality) ─── standalone
Phase 2C.6 (COMPETITOR suggestions) ─── depends on 2C.5
Phase 2C.7 (PARTNER scoring) ─── depends on 2C.3, 2C.4, 2C.5
```

---

## Recommended First Phase

**Phase 2C.1** — CompanyProfile semantic boundaries. This is pure documentation/comment work with zero behavioral risk and establishes the correct mental model for all subsequent phases.

**Phase 2C.2** — Relationship source of truth. This is low-risk and resolves the fundamental question of where relationship metadata lives before any contract or metric work begins.

These two phases can be executed in parallel.
