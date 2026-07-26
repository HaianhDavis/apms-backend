# Phase 2C Implementation Plan

**Status**: COMPLETED

## Purpose

This plan divides the work identified in the Phase 2C.0 data-model audit into small, safe implementation phases. Each phase is designed to be independently deployable and backward-compatible.

---

## Phase 2C.1 — Correct CompanyProfile Semantic Boundaries

**Status**: COMPLETED

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

**Status**: COMPLETED

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

### Phase 2C.3B: COMPLETED


### Goal
Create a `PartnerContract` SQL Server entity to govern contract lifecycle authority. Create `PartnerContractVersion` SQL Server entity for immutable history. Enable contract document upload and metadata extraction.

**Linkage Rules:**
`PartnerContract` official metadata in SQL Server.
`PartnerContractVersion` immutable history in SQL Server.
`RawDocument` original file in MongoDB.
Application-level cross-database reference by `rawDocumentId`.
No contract scores or KPI actuals in this phase.

### Files likely affected
- New: `PartnerContract.java` (SQL Server entity)
- New: `PartnerContractVersion.java` (SQL Server entity)
- New: `PartnerContractRepository.java` (Spring Data JPA)
- New: `PartnerContractVersionRepository.java` (Spring Data JPA)
- New: `PartnerContractService.java`
- New: `PartnerContractController.java`
- New: Contract DTOs (request/response)
- `SqlServerConfig.java` — Add new repository package
- `AuditAction.java` — Add new contract actions

---

## Phase 2C.4 — RoleMetricRecord Foundation

**Status**: COMPLETED

### Goal
Design the factual metric-record layer required for later PARTNER evaluation. `RoleMetricRecord` must store approved business measurements (targets, actuals, measurement periods) as factual input without calculating score properties.

### Database changes
- SQL Server: New `role_metric_records` and `role_metric_record_versions` tables.
- SQL Server: New `role_metric_evidences` and `role_metric_evidence_versions` tables.
- Foreign keys handling circular references (working copy vs approved version).

### API changes
- New CRUD endpoints under `/api/v1/projects/{projectId}/role-metrics`
- PATCH endpoints for draft modifications.
- Approval, revision, and evidence attachment workflows.
- Immutable version history endpoints.

### Migration risks
Low — new entities with no dependency on existing scoring tables.

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

## Phase 2C.5 — Scoring-Specific AI Suggestion Quality Model

### Phase 2C.5A — Scoring-Specific AI Suggestion Quality Foundation
**Status**: COMPLETED

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

### Phase 2C.6: COMPETITOR Six-Criterion Automatic Suggestions
**Status**: COMPLETED

#### Semantic Checklist
- [x] Growth Momentum preconditions
- [x] Structured Competitive Threat evidence
- [x] External Category Mapping
- [x] Legacy vs Canonical Overlap routes
- [x] Success Path tests
- [x] Repository Documentation

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

**Status**: COMPLETED
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

### Legacy Overlap Behavior Documentation

The legacy overlap behavior for `productMarketOverlapScore` has been documented:
Both `/api/v1/role-evaluations/{evaluationId}/product-market-overlap/suggest` and the unified route `/api/v1/role-evaluations/{evaluationId}/criteria/{criterionKey}/suggest` (when `criterionKey` is `productMarketOverlapScore`) call the same underlying method: `CompetitorComparisonService.suggestProductMarketOverlap`.

**Behavior:**
- Complete-data deterministic result remains backward-compatible.
- Missing components are not treated as 0 and not treated as 50. Missing components are NOT silently renormalized. They simply contribute 0 to the un-normalized sum, maintaining the natural score penalty.
- The unified canonical route returns `NEEDS_MORE_DATA` when required dimensions (productNameOverlap and at least two other dimensions) are incomplete.
- Tests proving this behavior have been added in `CompetitorComparisonLegacyBehaviorTest`.

---

## Phase 2C.5 — PARTNER Role Evaluation Drafts & Feedback

### Goal
Design the criterion-level evaluation layer for PARTNER companies to consume approved factual and documentary data and produce reviewable criterion evaluation drafts without calculating weights, numerical scores, or creating ScoreSnapshots.

### Architecture & Persistence
- Reuse existing MongoDB `RoleEvaluationDraft` for working draft state.
- Create a new immutable `RoleEvaluationVersion` in MongoDB to represent the approved state, bypassing `ScoreSnapshot` which is tightly coupled to `RoleScoringEngine` numeric outputs. No update or delete APIs for approved versions.
- Pointers (`currentApprovedVersionId`) are stored on the Draft, not the Version. `isCurrentApproved` is completely removed from the immutable schema.
- Data sources are pinned to their explicit *Approved Version* using typed `ApprovedSourceReference` objects (`sqlSourceId` vs `mongoSourceId` exclusivity enforced). Immutable `CompanyProfileVersion` replaces mutable profile usage.
- Use explicit `EvaluationPeriod` (type, asOfDate, start, end). PERIOD metrics must be *fully contained*; POINT_IN_TIME must be exact. Highest-approved deduplication is enforced.

### AI Suggestions Constraints
- Output must be purely qualitative (`criterionKey`, `rationale`, `missingDataNotes`, `confidence`, `evidenceReferenceIds`).
- Explicitly forbidden from outputting `overallScore`, `weights`, or numeric criterion scores (`criterionScore`, `suggestedRawScore`, etc.) for hybrid/ai-assisted criteria. Recursive nested AI tree validation rejects ANY numerical scores or unknown fields.
- Unrestricted maps like `calculationDetails` are forbidden for PARTNER evaluations; use typed provider metadata instead.
- `evidenceReferenceIds` must strictly be a subset of the server-pinned `sourceReferences`.

### Compatibility-First Criterion Key Fix
- `CanonicalRoleCriteria` currently has a mismatch (`capabilityComplementarityScore` vs `capabilityAndComplementarityScore`, and `governanceComplianceScore` vs `governanceAndRiskScore`).
- Implement read-time alias normalization, strict writes for new canonical keys, and idempotent SQL/Mongo migrations. Add typed collision detection (`BusinessValidationException`) for conflicting keys.

### Data Sufficiency & Workflow
- Implements strict typed evaluation completeness (SUFFICIENT, PARTIAL, INSUFFICIENT) per criterion matching exact `PartnerMetricDefinition` possibilities.
- Missing data remains `null`, with no 0 or 50 substitution.
- Manager approval allows PARTIAL data if a `partialApprovalJustification` is supplied; INSUFFICIENT blocks submission.

### Cross-Database Consistency & Strategies
- Implement `RoleEvaluationApprovalStrategy` pattern to separate Competitor (SQL Snapshot) and Partner (Mongo Immutable Version) flows.
- Partner approval uses an Outbox pattern (`RoleEvaluationApprovalOutbox` in Mongo) for idempotent SQL task sync to prevent cross-database partial failures.
- Uses `MongoTransactionManager` to atomically commit the Version, Pointer, and Outbox. Outbox worker processes `PENDING` states with atomic lease claiming (`lockedBy`, `leaseUntil`) and crash recovery logic.

### Permissions & APIs
- Strict path parameter validation: `record.projectId == path projectId` and strict task validation on every route.
- Assigned Staff: create draft, generate AI, edit criterion, attach evidence, submit, revisions.
- Manager: read working draft, approve, reject, request changes.
- Owner: read approved versions only via `/api/v1/projects/{projectId}/role-evaluations/{evaluationId}/current-approved` and exact version lists. No global `/approved/current`.

For detailed breakdown, refer to the active [Implementation Plan](file:///Users/davisiukem/.gemini/antigravity-ide/brain/0976f325-782b-44bd-808c-4a2af3cd4894/implementation_plan.md).

### Phase 2C.5: Evaluation Approval Data Isolation (Mongo outbox)

**Status:** COMPLETED (Superseded by 2C.5A–D)
- Replaced by Phase 2C.5A, 2C.5B, 2C.5C, and 2C.5D breakdowns.

### Phase 2C.5A: Canonical Keys, Evaluation Period, Approved Sources and Immutable Version Foundation

**Status:** COMPLETED

### Phase 2C.5B: PARTNER Context, Strict AI Suggestions and Data Sufficiency
**Status:** COMPLETED
- Implemented `CanonicalRoleCriteria` keys and Legacy Mapping normalization.
- Implemented `EvaluationPeriodType` & `EvaluationPeriod` with strict validation.
- Implemented `ApprovedSourceType` & `ApprovedSourceReference` with type-specific cross-source exclusions and missing fields checking.
- Implemented `RoleEvaluationVersion` immutable MongoDB foundation with `evaluationId` + `versionNumber` unique indexing.
- Established Migration Artifacts mapping `capabilityComplementarityScore` and `governanceComplianceScore` to canonical forms.
- Restricted AI and Calculation Details (Ensure purely qualitative schema).
- Data Sufficiency Definitions (Sufficient, Partial, Insufficient data states).

### Phase 2C.5C: PARTNER Submission, Manager Approval, Immutable Evaluation Version and Outbox Synchronization
**Status:** COMPLETED
- PARTNER submit/approve/request-revision workflow
- Immutable `RoleEvaluationVersion`
- Mongo transactional outbox
- SQL durable receipt and idempotent processing
- Retry/dead-letter/batch/ownership behavior
- COMPETITOR behavior unchanged
- PARTNER approval performs no scoring/AHP/ScoreSnapshot
- Mongo and SQL are not a distributed/XA transaction

### Phase 2C.5D: PARTNER Outbox Payload Integrity and Durable Hash Provenance
**Status:** COMPLETED
- Deterministic canonical SHA-256 hashing for `RoleEvaluationOutboxPayload`.
- `payloadHash` added to `RoleEvaluationOutboxEvent` (format `v1:sha256:<lowercase-hex>`).
- Hash verified post-claim before SQL processing.
- Missing/mismatched hash routed to DEAD_LETTER (no retry, safe ownership loss).
- SQL receipt includes `payload_hash` in `processed_outbox_events`.

## Phase 2C.8 — POTENTIAL_PARTNER Scoring

**Status:** COMPLETED

## Phase 2C.9 — CUSTOMER Scoring

**Status:** COMPLETED
