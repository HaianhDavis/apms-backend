# Competitor Scoring Data Audit

> [!WARNING]
> **Phase 2B Corrections Applied to this Document:**
> 1. `evaluatedRole` is derived from `Project.targetRelationshipType`.
> 2. The client does not select the official role.
> 3. `RoleEvaluationDraft` uses a dedicated `RoleEvaluationStatus` enum instead of `SubmissionStatus`.
> 4. `CompanyProfile.id` and `CompanyProfile.companyId` semantics have been resolved (`id` is Mongo ObjectID, `companyId` is UUID).
> 5. Product overlap compares `Product.name` and reviewed `Product.category` values.
> 6. Missing overlap components are not silently reweighted without coverage warnings.
> 7. Phase 2B automatically proposes only `productMarketOverlapScore`.
> 8. Cross-database approval is idempotent but is not described as one ACID transaction.

# COMPETITOR Scoring Data Audit
**Phase 2A — Audit and Design Only**
**Document Type:** Data Audit
**Version:** 1.0
**Date:** 2026-07-12
**Status:** FINAL — No Runtime Changes

> This document is audit and design only. No Java source, SQL schema, tests, entities, APIs, or scoring behavior were modified during this phase.

---

## 1. Scope

This audit supports COMPETITOR criterion scoring for the APMS canonical role-scoring framework.

**COMPETITOR criteria (fixed — do not rename, add, remove, or merge):**

| Criterion Key              | Direction | Illustrative AHP Weight |
|----------------------------|-----------|------------------------|
| marketPositionScore        | THREAT    | 0.20                   |
| productMarketOverlapScore  | THREAT    | 0.16                   |
| competitiveCapabilityScore | THREAT    | 0.20                   |
| strategicIntentScore       | THREAT    | 0.11                   |
| growthMomentumScore        | THREAT    | 0.09                   |
| competitiveThreatScore     | THREAT    | 0.24                   |

**Weights metadata:**
- weightingMethod = AHP
- weightSource = ILLUSTRATIVE
- weightVersion = ILLUSTRATIVE_AHP_V1
- ruleSetVersion = ROLE_SCORING_V1

These weights are **illustrative, not expert-approved**.

**Owner / Reference company:** FPT Corporation (`companyId = 6a31a0000000000000000001`)
**Role source:** `Project.targetRelationshipType = COMPETITOR_OF`
**Role mapping:** `COMPETITOR_OF` → `CompanyRole.COMPETITOR` (verified in `RelationshipTypeToCompanyRoleMapper`)

---

## 2. Actual Java Field Inventory

### 2.1 CompanyProfile (MongoDB company_profiles)

Source: `com.apms.domain.profile.CompanyProfile`

**IMPORTANT STRUCTURAL NOTES:**
- There is NO separate `BusinessInfo` class. Business fields live inside `CompanyProfile.Business` (nested static class).
- There is NO package-level `CompanySize` class. The `CompanySize` nested class is defined within `CompanyProfile` and `CompanyCandidate`.
- Correct Java field path notation: `companySize.employeeCount` (where `companySize` is `CompanyProfile.companySize`, an embedded `CompanyProfile.CompanySize` field).

| Java Field Path | Java Type | Nullable | List? | Notes |
|---|---|---|---|---|
| `id` | `String` | No | No | MongoDB `_id` |
| `companyId` | `String` | No | No | UUID; also used in Neo4j and SQL score_snapshots |
| `identity.legalName` | `String` | Yes | No | TextIndexed |
| `identity.tradeName` | `String` | Yes | No | |
| `identity.taxCode` | `String` | Yes | No | |
| `identity.registrationNumber` | `String` | Yes | No | |
| `business.industries` | `List<String>` | Yes | Yes | |
| `business.businessModel` | `String` | Yes | No | |
| `business.products` | `List<Product>` | Yes | Yes | Each Product has name, category, description |
| `business.markets` | `List<String>` | Yes | Yes | |
| `business.targetCustomers` | `List<String>` | Yes | Yes | |
| `companySize.employeeTier` | `String` | Yes | No | E.g., ">40,000" |
| `companySize.employeeCount` | `Integer` | Yes | No | |
| `companySize.revenueTier` | `String` | Yes | No | E.g., "Large Enterprise" |
| `contact.website` | `String` | Yes | No | |
| `contact.emails` | `List<String>` | Yes | Yes | |
| `contact.phones` | `List<String>` | Yes | Yes | |
| `contact.addresses` | `List<Address>` | Yes | Yes | Each Address has type, fullAddress, city, country |
| `insights.strengths` | `List<String>` | Yes | Yes | Free text — not suitable for COMPETITOR scoring |
| `insights.weaknesses` | `List<String>` | Yes | Yes | Free text — not suitable |
| `insights.opportunities` | `List<String>` | Yes | Yes | Free text — not suitable |
| `insights.threats` | `List<String>` | Yes | Yes | Free text — not suitable |
| `financial.revenue` | `BigDecimal` | Yes | No | No currency-period defined |
| `financial.revenueCurrency` | `String` | Yes | No | Currency code |
| `financial.revenueGrowth` | `BigDecimal` | Yes | No | **No time period defined in model** |
| `financial.debtRatio` | `BigDecimal` | Yes | No | |
| `financial.profitMargin` | `BigDecimal` | Yes | No | |
| `financial.fundingStage` | `String` | Yes | No | |
| `financial.profitability` | `String` | Yes | No | |
| `market.marketShare` | `BigDecimal` | Yes | No | Percentage; no market scope defined |
| `market.brandRank` | `Integer` | Yes | No | No ranking body defined |
| `market.clientCount` | `Long` | Yes | No | |
| `market.mainMarkets` | `List<String>` | Yes | Yes | |
| `innovation.patents` | `Integer` | Yes | No | |
| `innovation.rdInvestmentPercent` | `BigDecimal` | Yes | No | Percentage of revenue |
| `innovation.techStack` | `List<String>` | Yes | Yes | |
| `innovation.techMaturityLevel` | `Integer` | Yes | No | Integer scale; no defined range |
| `innovation.productInnovationRate` | `BigDecimal` | Yes | No | No units defined |
| `innovation.technologyCapabilities` | `List<String>` | Yes | Yes | |
| `risk.legalRisk` | `String` | Yes | No | Free text |
| `risk.financialRisk` | `String` | Yes | No | Free text |
| `risk.reputationRisk` | `String` | Yes | No | Free text |
| `risk.securityRisk` | `String` | Yes | No | Free text |
| `risk.conflictOfInterestRisk` | `String` | Yes | No | Free text |
| `risk.supplyInterruptionRisk` | `String` | Yes | No | Free text |
| `risk.dependencyRisk` | `String` | Yes | No | Free text |
| `risk.overallRiskLevel` | `String` | Yes | No | Free text — not suitable for COMPETITOR scoring |
| `compliance.status` | `String` | Yes | No | |
| `compliance.qualityCertifications` | `List<String>` | Yes | Yes | |
| `compliance.securityCertifications` | `List<String>` | Yes | Yes | |
| `compliance.antiCorruptionPolicy` | `String` | Yes | No | |
| `compliance.laborCompliance` | `String` | Yes | No | |
| `compliance.environmentalPolicy` | `String` | Yes | No | |
| `sourceRefs.projectIds` | `Set<String>` | Yes | Yes | |
| `sourceRefs.importJobIds` | `Set<String>` | Yes | Yes | |
| `sourceRefs.rawDocumentIds` | `Set<String>` | Yes | Yes | Source document provenance |
| `sourceRefs.candidateIds` | `Set<String>` | Yes | Yes | |
| `reviewStatus` | `String` | Yes | No | "APPROVED", "UNVERIFIED", etc. |
| `tags` | `List<String>` | Yes | Yes | |
| `version` | `Integer` | Yes | No | Profile version number |
| `metadata.createdBy` | `String` | Yes | No | |
| `metadata.createdAt` | `LocalDateTime` | Yes | No | |
| `metadata.lastModifiedBy` | `String` | Yes | No | |
| `metadata.updatedAt` | `LocalDateTime` | Yes | No | |

### 2.2 CompanyProfileVersion (MongoDB company_profile_versions)

Source: `com.apms.domain.profile.CompanyProfileVersion`

| Java Field | Java Type | Notes |
|---|---|---|
| `id` | `String` | MongoDB `_id` |
| `companyProfileId` | `String` | References CompanyProfile |
| `companyId` | `String` | UUID |
| `version` | `Integer` | Version number |
| `snapshot` | `Map<String, Object>` | Full serialized CompanyProfile snapshot |
| `createdFromProposalId` | `String` | Source proposal |
| `createdFromProjectId` | `Long` | Source project |
| `createdFromTaskId` | `Long` | Source task |
| `sourceDocumentIds` | `List<String>` | Document provenance |
| `changeSummary` | `String` | Human-readable change description |
| `createdBy` | `Long` | Account ID |
| `createdAt` | `LocalDateTime` | Version creation timestamp |

**Key finding:** `CompanyProfileVersion` stores the complete JSON snapshot. The `snapshot` field is `Map<String, Object>` — field semantics are not enforced at the version level. Cross-version comparison requires reviewed interpretation.

### 2.3 Project (SQL projects)

| Java Field | Java Type | Notes |
|---|---|---|
| `id` | `Long` | PK |
| `projectName` | `String` | |
| `projectType` | `ProjectType` | `UPDATE_EXISTING_COMPANY`, `RESEARCH_NEW_COMPANY` |
| `targetCompanyProfileId` | `String` | MongoDB companyId; nullable |
| `targetCompanyName` | `String` | |
| `targetRelationshipType` | `RelationshipType` | **Official role source** |
| `description` | `String` | |
| `status` | `ProjectStatus` | |
| `createdByAccount` | `Account` | FK |

RelationshipType enum: `PARTNER_WITH`, `COMPETITOR_OF`, `SUPPLIER_OF`, `CUSTOMER_OF`, `POTENTIAL_PARTNER_OF`

### 2.4 ProjectTask (SQL project_tasks)

| Java Field | Java Type | Notes |
|---|---|---|
| `id` | `Long` | PK |
| `project` | `Project` | FK |
| `assignedToAccount` | `Account` | FK; nullable |
| `title` | `String` | |
| `description` | `String` | |
| `status` | `TaskStatus` | `TODO`, `IN_PROGRESS`, `IN_REVIEW`, `DONE`, `BLOCKED`, `CANCELLED` |
| `taskType` | `TaskType` | `DOCUMENT_COLLECTION`, `COMPANY_DATA_PREPARATION`, `GENERAL_TASK` |
| `priority` | `TaskPriority` | |
| `dueDate` | `LocalDateTime` | |
| `createdByAccount` | `Account` | FK |
| `completedAt` | `LocalDateTime` | |

**Note:** No `ROLE_EVALUATION` TaskType exists. A new enum value is needed in Phase 2B.

### 2.5 ProjectTaskSubmission (SQL project_task_submissions)

| Java Field | Java Type | Notes |
|---|---|---|
| `id` | `Long` | PK |
| `projectTask` | `ProjectTask` | FK |
| `project` | `Project` | FK |
| `submittedByAccount` | `Account` | FK |
| `submissionType` | `SubmissionType` | Existing: `COMPANY_CANDIDATE`, `PROFILE_UPDATE_PROPOSAL`, `DOCUMENT_COLLECTION`, `COMPANY_REPORT`, `OTHER` |
| `targetEntityType` | `String` | Entity type reference |
| `targetEntityId` | `String` | Entity ID reference |
| `status` | `SubmissionStatus` | `DRAFT`, `SUBMITTED`, `IN_REVIEW`, `APPROVED`, `REJECTED`, `APPLIED` |
| `note` | `String` | |
| `submittedAt` | `LocalDateTime` | |
| `reviewedByAccount` | `Account` | FK; nullable |
| `reviewedAt` | `LocalDateTime` | |
| `reviewComment` | `String` | |

**Key finding:** `ProjectTaskSubmission` supports the full Staff → Manager lifecycle via `SubmissionStatus`. A new `SubmissionType.ROLE_EVALUATION` value would enable reuse. The `targetEntityId` can reference the `RoleEvaluationDraft` MongoDB ID.

### 2.6 CompanyProfileUpdateProposal — Decision: Not Reusable

**Decision: CompanyProfileUpdateProposal MUST NOT be reused as a scoring evaluation draft.**

Rationale:
- It represents proposed changes to **factual profile data** (identity, business model, financial facts).
- A scoring draft evaluates **approved factual data** using criterion-level inputs and evidence.
- A profile update proposal writes to the profile; a scoring draft reads approved data and produces a scored evaluation.
- Reusing it would conflate factual data management with scored assessment — two architecturally distinct concerns.
- The `proposedX: Map<String, Object>` structure per section is designed for field-level merge, not criterion scoring.

---

## 3. Reference Context Field Structure

Source: `com.apms.domain.profile.service.ReferenceCompanyContextService`

The service maps the owner CompanyProfile to `ReferenceCompanyContextResponse` with these behaviors:

| Response Field | Source Field | Normalized? | Notes |
|---|---|---|---|
| `companyProfileId` | `profile.id` | No | |
| `profileVersion` | `profile.version` | No | Version provenance preserved |
| `legalName` | `identity.legalName` | trim() | |
| `tradeName` | `identity.tradeName` | trim() | |
| `industries` | `business.industries` | trim, dedup, filter blank | |
| `businessModel` | `business.businessModel` | trim() | |
| `products` | `business.products` | Not normalized | Passed as-is |
| `markets` | `business.markets` | trim, dedup, filter blank | |
| `targetCustomers` | `business.targetCustomers` | trim, dedup, filter blank | |
| `employeeCount` | `companySize.employeeCount` | No | |
| `employeeTier` | `companySize.employeeTier` | No | |
| `revenueTier` | `companySize.revenueTier` | No | |
| `headquarters` | `contact.addresses` | No | |
| `website` | `contact.website` | trim() | |
| `financial` | `profile.financial` | No | Passed as-is |
| `market` | `profile.market` | No | Passed as-is |
| `innovation` | `profile.innovation` | No | Passed as-is |
| `risk` | `profile.risk` | No | Passed as-is |
| `compliance` | `profile.compliance` | No | Passed as-is |
| `sourceDocumentIds` | `sourceRefs.rawDocumentIds` | No | |

**Comparison availability checks implemented (7 groups):**
1. `strategicFit` — requires `business.industries`, `business.products`, `business.markets`
2. `capabilityComplementarity` — requires `innovation.technologyCapabilities`
3. `productMarketOverlap` — requires `business.products`, `business.markets`
4. `competitiveCapabilityComparison` — requires `innovation.technologyCapabilities` + `companySize`
5. `marketPositionComparison` — requires any of `market.marketShare`, `market.brandRank`, `market.clientCount`
6. `financialComparison` — requires any of `financial.revenue`, `financial.profitMargin`, `financial.revenueGrowth`
7. `complianceComparison` — requires any of `compliance.status`, `qualityCertifications`, `securityCertifications`

---

## 4. FPT Seeded Data Availability

FPT companyId: `6a31a0000000000000000001`
Source: `AssistantDemoDataSeeder`

| Field Path | Seeded? | Seeded Value Summary | COMPETITOR-Suitable? | Limitation |
|---|---|---|---|---|
| `identity.legalName` | YES | "Công ty Cổ phần FPT" | No — identity only | |
| `identity.tradeName` | YES | "FPT Corporation" | No | |
| `business.industries` | YES | `["Information Technology", "Software Outsourcing", "AI", "Cloud Services"]` | YES — overlap comparison | High-level categories only |
| `business.businessModel` | YES | Description string | Partial — descriptive | Not structured data |
| `business.products` | YES | 3 products (Software Outsourcing, AI Solutions, Cloud Services) | YES — product overlap | Very high-level |
| `business.markets` | YES | `["Vietnam", "Japan", "United States", "Europe", "APAC"]` | YES — geographic overlap | Geographic only |
| `business.targetCustomers` | YES | `["Banking", "Manufacturing", "Healthcare", "Public Sector"]` | YES — customer overlap | Broad segments |
| `companySize.employeeCount` | YES | `40000` | YES — scale comparison | Single point; no history |
| `companySize.employeeTier` | YES | `">40,000"` | YES — tier comparison | |
| `companySize.revenueTier` | YES | `"Large Enterprise"` | Partial — tier only | No actual revenue figure |
| `contact.addresses` | YES | "Hanoi, Vietnam" | No | HQ location only |
| `financial.revenue` | NO | NOT SEEDED | NO | Missing |
| `financial.revenueCurrency` | NO | NOT SEEDED | NO | Missing |
| `financial.revenueGrowth` | NO | NOT SEEDED | NO | Missing; also no period defined |
| `financial.debtRatio` | NO | NOT SEEDED | NO | Missing |
| `financial.profitMargin` | NO | NOT SEEDED | NO | Missing |
| `financial.fundingStage` | NO | NOT SEEDED | NO | Missing |
| `financial.profitability` | NO | NOT SEEDED | NO | Missing |
| `market.marketShare` | NO | NOT SEEDED | NO | Missing |
| `market.brandRank` | NO | NOT SEEDED | NO | Missing |
| `market.clientCount` | NO | NOT SEEDED | NO | Missing |
| `market.mainMarkets` | NO | NOT SEEDED | NO | Missing |
| `innovation.patents` | NO | NOT SEEDED | NO | Missing |
| `innovation.rdInvestmentPercent` | NO | NOT SEEDED | NO | Missing |
| `innovation.techStack` | NO | NOT SEEDED | NO | Missing |
| `innovation.techMaturityLevel` | NO | NOT SEEDED | NO | Missing |
| `innovation.productInnovationRate` | NO | NOT SEEDED | NO | Missing |
| `innovation.technologyCapabilities` | NO | NOT SEEDED | NO | Missing — blocks competitiveCapabilityScore |
| `risk.*` | NO | NOT SEEDED | NO | All risk fields absent |
| `compliance.*` | NO | NOT SEEDED | NO | All compliance fields absent |
| `insights.strengths` | YES | 3 descriptive strings | NO | Free text; not parseable |
| `reviewStatus` | YES | `"APPROVED"` | YES — prerequisite check | |
| `version` | YES | `1` | YES — version provenance | Only 1 version; no history |

**Summary:** FPT has sufficient business taxonomy and size data for product/market overlap and scale comparison. FPT has **zero** financial, market, and innovation metric values seeded. This blocks automatic hybrid scoring for `marketPositionScore`, `competitiveCapabilityScore`, and all quantitative comparisons.

---

## 5. Target Profile Data Availability

### 5.1 AI Extraction Coverage (ExtractedCompanyData)

The AI extraction DTO (`com.apms.domain.ai.dto.ExtractedCompanyData`) supports:
- `legalName`, `tradeName`, `taxCode`, `industries`, `businessModel`, `products`, `markets`, `targetCustomers`
- `employeeTier` (String) — **NOT `employeeCount` (Integer)**
- `financial.*` — full FinancialInfo fields
- `market.*` — full MarketInfo fields
- `innovation.*` — full InnovationInfo fields
- `risk.*`, `compliance.*`

**Key extraction gap:** `employeeCount (Integer)` is not in `ExtractedCompanyData`. Extraction only provides `employeeTier (String)`.

All AI-extracted values require human review and profile approval before use as criterion inputs.

### 5.2 Field Availability for Targets

| Field | AI Extraction | Review Required | Typically in Approved Profile |
|---|---|---|---|
| `business.industries` | YES | YES | YES — usually present |
| `business.products` | YES | YES | YES — usually present |
| `business.markets` | YES | YES | YES — usually present |
| `business.targetCustomers` | YES | YES | YES — usually present |
| `companySize.employeeTier` | YES (string) | YES | YES — usually present |
| `companySize.employeeCount` | NO (not in extraction DTO) | N/A | Partial — often missing |
| `companySize.revenueTier` | Partial | YES | Partial |
| `financial.revenue` | YES | YES | Partial — often missing |
| `financial.revenueGrowth` | YES | YES | Partial — **no period defined** |
| `financial.profitMargin` | YES | YES | Rare |
| `market.marketShare` | YES | YES | Rare — hard to verify |
| `market.brandRank` | YES | YES | Rare |
| `market.clientCount` | YES | YES | Partial |
| `market.mainMarkets` | YES | YES | Partial |
| `innovation.technologyCapabilities` | YES | YES | Partial |
| `innovation.techStack` | YES | YES | Partial |
| `innovation.patents` | YES | YES | Rare |
| `innovation.rdInvestmentPercent` | YES | YES | Rare |
| `innovation.techMaturityLevel` | YES | YES | Rare — subjective |
| `innovation.productInnovationRate` | YES | YES | Rare — no units |

---

## 6. Historical Data Gaps

`CompanyProfileVersion.snapshot` stores a complete profile snapshot with `createdAt` timestamp. However:

1. `revenueGrowth` has no `period` field — a delta is uninterpretable.
2. Profile-version deltas may reflect data corrections or better extraction, not real business change.
3. FPT has only 1 seeded version — no historical baseline exists.
4. Most target profiles also have only 1 version in practice.
5. `employeeCount` is rarely in extraction data, so employee growth is usually unmeasurable.
6. `market.marketShare` and `market.clientCount` are rarely populated across multiple versions.

**Conclusion:** `growthMomentumScore` cannot currently be auto-calculated from stored data. It must be MANUAL_REVIEWED with explicit period documentation by the reviewer.

---

## 7. Operational Data Gaps

The following inputs required for `competitiveThreatScore` and `strategicIntentScore` are NOT currently stored in any APMS entity:

| Required Input | Gap |
|---|---|
| FPT customer displacement by target | No entity — requires internal BD data |
| Active competing bids | No entity |
| Contract losses to target | No entity |
| Pricing pressure evidence | No entity |
| Market share loss attributable to target | No entity |
| Target wins in FPT priority segments | No entity |
| Customer switching to target | No entity |
| Verified competitive intelligence | No entity |
| Announced market entry plans | Requires external evidence |
| Acquisitions targeting overlapping segments | Requires external evidence |

These gaps require the `RoleEvaluationDraft` to include an evidence record model with full provenance.

---

## 8. Criterion Input Matrix

| Criterion | Owner Field Path | Target Field Path | Java Type | FPT Data Available | Typical Target Available | External Evidence Needed | Internal Operational Data | Auto-Calc Possible | Human Review Required | Minimum Sufficient Input | Missing-Data Result |
|---|---|---|---|---|---|---|---|---|---|---|---|
| marketPositionScore | market.marketShare, market.brandRank, market.clientCount, companySize.revenueTier | Same fields | BigDecimal/Integer/Long/String | NO (no market.* seeded) | Partial | Partial | No | HYBRID (if >=2 indicators available) | Yes — single-indicator case | >=2 of: marketShare, brandRank, clientCount, revenueTier | null |
| productMarketOverlapScore | business.products, business.industries, business.markets, business.targetCustomers | Same | List<String>/List<Product> | YES (taxonomy available) | YES — usually | No | No | HYBRID — auto Jaccard; reviewed synonyms | Yes — taxonomy mapping | products + one of: markets, industries, targetCustomers | null |
| competitiveCapabilityScore | innovation.technologyCapabilities, companySize.employeeCount, companySize.revenueTier, innovation.patents, innovation.rdInvestmentPercent | Same | List<String>/Integer/String/BigDecimal | NO (no innovation.* seeded) | Partial | No | No | HYBRID (if technologyCapabilities + scale indicator present) | Yes | technologyCapabilities + one scale indicator | null |
| strategicIntentScore | N/A (FPT not primary source) | External/internal evidence records | N/A | N/A | NO | YES | Partial | NO — MANUAL_REVIEWED | Always | >=1 verified evidence record | null |
| growthMomentumScore | financial.revenueGrowth (no period), profile versions | financial.revenueGrowth, profile versions | BigDecimal | NO — not seeded | Partial | Partial | No | MANUAL_REVIEWED (no reliable time series) | Always | Reviewed period-defined metric | null |
| competitiveThreatScore | N/A | Internal BD records, external evidence | N/A | N/A | NO | YES | YES | NO — MANUAL_REVIEWED | Always | >=1 verified direct-threat evidence record | null |

Source type legend:
- OWNER_PROFILE — FPT business taxonomy, size tier
- TARGET_PROFILE — target approved profile fields
- PROFILE_VERSION — multi-version delta
- INTERNAL_OPERATIONAL_DATA — competitive deals, customer displacement
- USER_REVIEWED_INPUT — manual criterion inputs
- EXTERNAL_EVIDENCE — strategic announcements, market reports
- DERIVED_COMPARISON — Jaccard scores, relative comparisons

---

## 9. Automatic/Manual Classification

| Criterion | Classification | Rationale |
|---|---|---|
| marketPositionScore | HYBRID | Can be partially auto-calculated from market indicators and relative size tiers; requires Manager review when only 1 indicator is available or indicators conflict. Currently blocked because FPT has no market.* fields seeded. |
| productMarketOverlapScore | HYBRID | Jaccard similarity is deterministic for exact-match components; synonym/taxonomy mapping requires human review; AI suggestions require Manager confirmation. FPT business taxonomy is available. |
| competitiveCapabilityScore | HYBRID | Technology capability and scale comparison can be auto-proposed; relative scoring versus FPT is blocked because FPT innovation fields are not seeded. Requires reviewed manual input until FPT profile is enriched. |
| strategicIntentScore | MANUAL_REVIEWED | Cannot be derived from stored profile data. Requires external evidence with verified source, date, reliability level, and explicit human confirmation. |
| growthMomentumScore | MANUAL_REVIEWED | `revenueGrowth` has no period; profile-version deltas are unverified; no time-series storage. Auto-calculation is blocked until reviewed period-defined metrics exist. |
| competitiveThreatScore | MANUAL_REVIEWED | Requires direct operational evidence of FPT exposure. Not stored in any current entity. Cannot be derived from profile data alone. |

---

## 10. Version and Provenance Requirements

A `RoleEvaluationDraft` must bind to fixed versions at creation:

| Field | Source | Behavior |
|---|---|---|
| targetCompanyProfileId | Project.targetCompanyProfileId | Fixed at draft creation |
| targetProfileVersion | CompanyProfile.version at draft creation time | Fixed |
| referenceCompanyProfileId | Owner organization companyId | Fixed |
| referenceProfileVersion | FPT CompanyProfile.version at draft creation | Fixed |
| ruleSetVersion | ROLE_SCORING_V1 | Fixed |
| weightVersion | ILLUSTRATIVE_AHP_V1 | Fixed |

Staleness events:
- Newer target profile version approved → staleTargetProfile = true
- Newer FPT profile approved → staleReferenceProfile = true
- Newer rule set activated → staleRuleSet = true

Manager must explicitly confirm or reject stale versions before approval. Approved snapshots are immutable and their provenance cannot be rewritten.

---

## 11. Workflow Reuse Analysis

### Existing Components Reusable for COMPETITOR Evaluation

| Component | Reusable? | What Is Reused |
|---|---|---|
| ProjectTask | YES (new TaskType needed) | Task lifecycle, assignment, status |
| ProjectTaskSubmission | NO | A dedicated `RoleEvaluationStatus` handles draft lifecycle |
| SubmissionStatus | NO | Replaced by `RoleEvaluationStatus` for drafts |
| TaskStatus | YES | TODO → IN_PROGRESS → IN_REVIEW → DONE |
| RoleScoringEngine | YES — unchanged | Accepts criterion scores and weights |
| CanonicalScoreSnapshotService | YES — unchanged | Creates immutable snapshots |
| RelationshipTypeToCompanyRoleMapper | YES | COMPETITOR_OF → COMPETITOR |
| ReferenceCompanyContextService | YES | Provides FPT reference context |
| CompanyProfileVersionRepository | YES | Version existence checks |

### What Must Be Added in Phase 2B

| New Component | Purpose |
|---|---|
| RoleEvaluationDraft (MongoDB) | Editable evaluation draft |
| EvidenceRecord (embedded) | Per-criterion evidence with provenance |
| CriterionInput (embedded) | Per-criterion reviewed input with audit |
| TaskType.ROLE_EVALUATION | New enum value |
| RoleEvaluationStatus | New enum for draft lifecycle |
| Stale-version detection service | Alert when profile/rule versions change |
| COMPETITOR comparison helpers | Jaccard, scale comparison |
| Reviewed taxonomy mapping storage | Synonym/category equivalence |

### Task Submission Lifecycle Support (Verified)

| Step | Supported |
|---|---|
| Staff creates draft | YES — new RoleEvaluationDraft entity |
| Staff prepares criterion inputs | YES — CRUD on draft |
| Staff submits draft | YES — RoleEvaluationStatus transitions to SUBMITTED |
| Draft enters review | YES — RoleEvaluationStatus.IN_REVIEW |
| Manager reviews criteria | YES — review step with comment |
| Manager approves | YES — RoleEvaluationStatus.APPROVED (idempotent, triggers snapshot) |
| Manager rejects | YES — RoleEvaluationStatus.REJECTED |
| Staff revises and resubmits | YES — via REVISION_REQUIRED status |
| Immutable snapshot created | YES — CanonicalScoreSnapshotService (triggered post-approval idempotently) |

---

## 12. Risks and Limitations

| Risk | Severity | Mitigation |
|---|---|---|
| FPT has no seeded market.* or innovation.* fields | HIGH — blocks most automatic COMPETITOR scoring | Require FPT profile enrichment before enabling hybrid criteria |
| revenueGrowth has no time period | HIGH — blocks growthMomentumScore auto-calc | Disallow auto-calculation; require manual input with defined period |
| techMaturityLevel is an unscaled Integer | MEDIUM — ambiguous without defined range | Document expected range (e.g., 1–5) before enabling |
| productInnovationRate has no units | MEDIUM | Cannot be compared automatically; manual input required |
| Profile-version deltas may reflect data corrections not real growth | MEDIUM | Always require Manager review of version deltas |
| competitiveThreatScore requires internal operational data | HIGH — no storage entity exists | Phase 2B must create evidence storage |
| No ROLE_EVALUATION TaskType | MEDIUM | Add enum value in Phase 2B |
| Cross-database approval transactions | MEDIUM | Implement cross-database approval as idempotent operations instead of distributed ACID transaction |
| AI may silently infer strategic intent from company descriptions | CRITICAL | Explicitly forbid AI confidence scores from becoming criterion values |

---

## 13. Recommended Phase 2B Implementation Scope

**Do not implement during Phase 2A.**

### New Entities
- `RoleEvaluationDraft` (MongoDB) — generic, role-neutral draft
  - Fields: id, projectId, taskId, targetCompanyProfileId, targetProfileVersion, referenceCompanyProfileId, referenceProfileVersion, evaluatedRole, ruleSetVersion, weightVersion, criterionInputsJson, criterionEvidenceJson, automaticSuggestionsJson, reviewStatus, submittedBy, submittedAt, reviewedBy, reviewedAt, reviewComment, staleTargetProfile, staleReferenceProfile, staleRuleSet, createdAt, updatedAt

### New Enum Values
- `TaskType.ROLE_EVALUATION`
- `RoleEvaluationStatus`

### New Services
- `RoleEvaluationDraftService` — CRUD, validation, stale-version detection
- `CompetitorComparisonService` — Jaccard similarity, scale comparison helpers
- `EvidenceValidationService` — evidence record validation

### Tests
- Unit tests for Jaccard similarity
- Unit tests for criterion input validation
- Unit tests for draft lifecycle state transitions
- Integration tests for Manager approval flow creating immutable snapshots

---

*Document generated as part of Phase 2A — COMPETITOR Scoring Data Audit and Rubric Specification.*
*No runtime scoring behavior was changed during this phase.*
