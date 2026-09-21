# Company and Relationship Data Model

## 1. Purpose

This document establishes the canonical data-model separation for APMS. It defines which data belongs to which aggregate, which database is authoritative, and how a single company with multiple relationship roles is represented.

## 2. Core Separation Principle

```
CompanyProfile facts ≠ relationship facts ≠ contracts ≠ operational metrics ≠ AI suggestions ≠ approved scores
```

These six categories of data must not be conflated. A score criterion name (e.g., `marketPositionScore`) is NOT a factual CompanyProfile field. `Insights.strengths` is not the same as a reviewed `CriterionInput` for `strategicAlignmentScore`.

---

## 3. Canonical Aggregates

### A. CompanyProfile (MongoDB)

Contains **factual information about the company itself**, independent of any relationship with FPT.

**Current actual fields** (from [CompanyProfile.java](file:///Users/davisiukem/IdeaProjects/apms-backend/src/main/java/com/apms/domain/profile/CompanyProfile.java)):

| Section | Fields | Classification |
|---------|--------|----------------|
| `identity` | legalName, tradeName, taxCode, registrationNumber | COMPANY_FACT |
| `business` | industries, businessModel, products (name/category/description), markets, targetCustomers | COMPANY_FACT |
| `companySize` | employeeTier, employeeCount, revenueTier | COMPANY_FACT |
| `contact` | website, emails, phones, addresses | COMPANY_FACT |
| `financial` | revenue, revenueCurrency, revenueGrowth, debtRatio, profitMargin, fundingStage, profitability | COMPANY_FACT |
| `market` | marketShare, brandRank, clientCount, mainMarkets | COMPANY_FACT |
| `innovation` | patents, rdInvestmentPercent, techStack, techMaturityLevel, productInnovationRate, technologyCapabilities | COMPANY_FACT |
| `risk` | legalRisk, financialRisk, reputationRisk, securityRisk, conflictOfInterestRisk, supplyInterruptionRisk, dependencyRisk, overallRiskLevel | COMPANY_FACT |
| `compliance` | status, qualityCertifications, securityCertifications, antiCorruptionPolicy, laborCompliance, environmentalPolicy | COMPANY_FACT |
| `insights` | strengths, weaknesses, opportunities, threats | **AI_INSIGHT** |
| `sourceRefs` | projectIds, importJobIds, rawDocumentIds, candidateIds | Metadata/provenance |
| `metadata` | createdBy, createdAt, lastModifiedBy, updatedAt, deletedAt | Metadata |

**Important classifications:**

- **`insights` (SWOT)**: Currently stored in CompanyProfile. These are AI-generated analytical observations, NOT confirmed factual data. They should be clearly labeled as AI_INSIGHT. They are not criterion scores and not confirmed facts. **No field removal in this phase.** Document the semantic mismatch for future correction.
- **`risk.overallRiskLevel`**: This is a qualitative assessment field that may be AI-derived or manually assessed. Classify as COMPANY_FACT with caveat — it describes the company's general risk profile, not a role-specific evaluation score.
- **`risk.supplyInterruptionRisk`, `risk.dependencyRisk`**: These may be more relevant when the company is evaluated as a SUPPLIER, but they describe the company's own risk characteristics, not the FPT-company relationship. COMPANY_FACT.

**Must NOT contain:**
- Partner contracts or SLA data
- Contract KPI results
- Customer transactions
- Supplier delivery performance records
- Relationship satisfaction scores
- Criterion scores (businessValueContributionScore, strategicAlignmentScore, etc.)
- overallScore from role evaluation
- AI-generated criterion suggestions treated as confirmed facts

**Current status:**
- `identity`, `business`, `companySize`, `contact`, `financial`, `market`, `innovation`, `risk`, and `compliance` are the factual profile sections.
- `insights` is a legacy AI-generated analysis section still embedded in CompanyProfile.
- CompanyProfile currently contains no role criterion scores or overallScore.
- Therefore, the factual-score boundary is clean, but the factual-AI-insight boundary is not fully clean.

---

### B. CompanyRelationship (SQL + Neo4j)

**Important Semantics & Authority Rules:**
1. **Project Evaluation Scope**: `Project.targetRelationshipType` (SQL) is the sole authoritative source for the context of a project and role evaluation. The manager-selected `targetRelationshipType` determines the evaluated role. AI NEVER determines the official relationship type.
2. **Confirmed Organizational Relationship**: The official confirmation of an enterprise relationship must be explicit. `CandidateApprovedEvent` is legacy-only and must NOT be the foundation of the new relationship workflow. Do not design new Phase 2C behavior around `CandidateApprovedEvent`.
3. **Graph Projection**: Neo4j (`CompanyNode` and typed edges via [GraphService.java](file:///Users/davisiukem/IdeaProjects/apms-backend/src/main/java/com/apms/domain/graph/service/GraphService.java)) represents the confirmed relationship graph/projection. Neo4j MUST NOT override an explicit `Project.targetRelationshipType`.

**Current Implementation Details:**
The current implementation uses Neo4j typed relationship edges between `CompanyNode` instances. Relationship data stored on Neo4j edges:

| Property | Classification |
|----------|----------------|
| relationship type (edge label: `PARTNER_WITH`, `COMPETITOR_OF`, etc.) | RELATIONSHIP_FACT |
| confidenceScore | RELATIONSHIP_FACT |
| confirmedBy | RELATIONSHIP_FACT |
| confirmedAt | RELATIONSHIP_FACT |
| projectId | Provenance |
| candidateId | Provenance |

**Important:** Do not create a duplicate `CompanyRelationship` SQL or Mongo entity in this documentation phase. If additional relationship metadata is needed (startDate, endDate, relationshipStatus, accountManagerId), they should either augment the Neo4j edge or be tied to the `PartnerContract` lifecycle, rather than creating a competing `CompanyRelationship` entity.

---

### C. PartnerContract (NOT YET IMPLEMENTED)

Contracts are independent business entities. They must NOT be embedded in CompanyProfile.

**Current status:** No `PartnerContract` entity exists in the repository. No contract-related fields exist in CompanyProfile, CompanyCandidate, or any other entity.

**Current document infrastructure:**
- [RawDocument](file:///Users/davisiukem/IdeaProjects/apms-backend/src/main/java/com/apms/domain/document/RawDocument.java) stores uploaded files (PDF, DOCX, etc.) in MongoDB.
- `ImportJob` (SQL) tracks import batches.
- Documents are linked to projects via `projectId`.
- AI extraction processes documents into `AiExtractionCache` → `CompanyCandidate` → `CompanyProfile`.

**Gap:** No mechanism exists to tag, extract, or link contract metadata.

**Recommended Persistence Separation:**
- **SQL Server:** Structured `PartnerContract` metadata and lifecycle (contract number, reference company, partner company, relationship type, project linkage, dates, value, currency, status, account manager, raw document reference). SQL is preferable here for strict relational querying, foreign key constraints, and reliable lifecycle management.
- **RawDocument (MongoDB):** Storage of the original uploaded contract file.
- **MongoDB (only if needed):** Storage for extracted clauses, flexible AI analysis, contract extraction evidence, and draft extracted contract content (where schema flexibility is critical).

---

### D. RoleMetricRecord (NOT YET IMPLEMENTED)

Role-specific operational metrics are inputs for scoring, not scores themselves.

**Examples by role:**

| Role | Example Metrics | Classification |
|------|----------------|----------------|
| PARTNER | realizedRevenue, kpiCompleted, slaCompliant, deliverablesOnTime, satisfactionScore | OPERATIONAL_METRIC |
| CUSTOMER | contractValue, purchaseFrequency, renewalRate, paymentDelayRate | OPERATIONAL_METRIC |
| SUPPLIER | defectRate, onTimeDeliveryRate, leadTimeDays, fulfillmentRate | OPERATIONAL_METRIC |
| COMPETITOR | competingBids, customersLostToTarget, pricingPressure | EXTERNAL_SIGNAL |
| POTENTIAL_PARTNER | expectedDealValue, estimatedROI, collaborationOpportunities | OPERATIONAL_METRIC |

**Current status:** None of these fields exist anywhere in the repository. The gap is complete.

**Recommended database:** MongoDB (allows flexible, role-specific metric schemas without rigid SQL table proliferation).

---

### E. RoleEvaluationDraft (MongoDB — existing)

**Current implementation:** [RoleEvaluationDraft.java](file:///Users/davisiukem/IdeaProjects/apms-backend/src/main/java/com/apms/domain/score/draft/RoleEvaluationDraft.java)

Contains the in-progress evaluation with:
- `CriterionInput` — Staff-reviewed criterion scores (REVIEWED_CRITERION_INPUT)
- `AutomaticSuggestion` — AI-proposed scores (AI_CRITERION_SUGGESTION)
- `EvidenceRecord` — Supporting evidence links

**Classification:** Each `CriterionInput` is a REVIEWED_CRITERION_INPUT. Each `AutomaticSuggestion` is an AI_CRITERION_SUGGESTION. Neither is a COMPANY_FACT or an APPROVED_SCORE_RESULT.

---

### F. ScoreSnapshot (SQL Server — existing)

**Current implementation:** [ScoreSnapshot.java](file:///Users/davisiukem/IdeaProjects/apms-backend/src/main/java/com/apms/domain/score/ScoreSnapshot.java)

The immutable approved result containing:
- Reference and target company/version
- Evaluated role
- Reviewed criterion scores (JSON)
- Normalized criterion scores (JSON)
- AHP weights (JSON)
- overallScore
- ruleSetVersion, weightVersion
- sourceEvaluationDraftId
- approvalIdempotencyKey

**Classification:** All score fields are APPROVED_SCORE_RESULT.

**Legacy fields** (from pre-Phase-1 scoring):
- `partnerFitScore`, `competitionLevel`, `riskLevel`, `relationshipStrength`, `totalScore`, `factorsJson`, `ruleVersion` — LEGACY_OR_UNCLEAR. These are from the old scoring system and are nullable for canonical snapshots.

---

### G. OwnerStrategyContext (Conceptual - NOT YET IMPLEMENTED)

Contains **long-term priorities of FPT**. The current rubric must not assume that FPT `CompanyProfile` alone describes FPT strategy, priorities, needs, or capability gaps. `CompanyProfile` defines what FPT factually is and currently has.

**Conceptual fields:**
- `priorityIndustries`
- `priorityMarkets`
- `priorityProducts`
- `priorityCustomerSegments`
- `strategicGoals`

---

### H. ProjectEvaluationContext (Conceptual - NOT YET IMPLEMENTED)

Contains **needs and goals specific to one evaluation project**.

**Conceptual fields:**
- `requiredCapabilities`
- `capabilityGaps`
- `requiredResources`
- `desiredMarketAccess`
- `requiredCertifications`
- `requiredSecurityStandards`

**Important Distinction:**
- **CompanyProfile**: what FPT factually is and currently has.
- **OwnerStrategyContext**: long-term priorities of FPT.
- **ProjectEvaluationContext**: needs and goals specific to one evaluation project.

---

## 4. One-Company-Multiple-Roles Example

```
Company ABC (one CompanyProfile in MongoDB)
├── PARTNER_WITH FPT (Neo4j edge)
│   ├── RoleEvaluationDraft (PARTNER, MongoDB)
│   │   ├── businessValueContributionScore: 85
│   │   ├── strategicAlignmentScore: 70
│   │   └── ... (6 PARTNER criteria)
│   └── ScoreSnapshot (PARTNER, SQL)
│       └── overallScore: 78.2
│
└── SUPPLIER_OF FPT (Neo4j edge)
    ├── RoleEvaluationDraft (SUPPLIER, MongoDB)
    │   ├── qualityPerformanceScore: 90
    │   ├── deliveryPerformanceScore: 82
    │   └── ... (6 SUPPLIER criteria)
    └── ScoreSnapshot (SUPPLIER, SQL)
        └── overallScore: 84.5
```

- **One** CompanyProfile with factual data (identity, business, financials, etc.)
- **Separate** Neo4j relationship edges for each role
- **Separate** RoleEvaluationDraft for each role evaluation
- **Separate** ScoreSnapshot for each approved evaluation
- Scores are NEVER combined across roles

---

## 5. Database Ownership

| Aggregate | Database | Reason |
|-----------|----------|--------|
| CompanyProfile | **MongoDB** | Flexible nested document schema for varied company data. Existing implementation. |
| CompanyProfileVersion | **MongoDB** | Snapshot of profile at approval time. Existing implementation. |
| CompanyProfileUpdateProposal | **MongoDB** | Draft changes with field evidence. Existing implementation. |
| CompanyCandidate | **MongoDB** | AI-extracted draft company data. Existing implementation. |
| CompanyRelationship | **Neo4j** | Graph database optimized for relationship traversal. Existing implementation. |
| PartnerContract | **MongoDB** (proposed) | Flexible schema for varied contract types. NOT YET IMPLEMENTED. |
| RoleMetricRecord | **MongoDB** (proposed) | Role-specific metrics vary by relationship type. NOT YET IMPLEMENTED. |
| RoleEvaluationDraft | **MongoDB** | Draft evaluation with embedded criteria/evidence. Existing implementation. |
| ScoreSnapshot | **SQL Server** | Immutable approved scores with referential integrity. Existing implementation. |
| RoleScoreRuleSet | **SQL Server** | Canonical scoring rules. Existing implementation. |
| RoleCriterionRule | **SQL Server** | Per-criterion configuration. Existing implementation. |
| RawDocument | **MongoDB** | File metadata and content. Existing implementation. |
| AiExtractionCache | **MongoDB** | AI extraction results with field-level quality. Existing implementation. |
| ExternalDataItem | **MongoDB** | External signals (news, market data). Existing implementation. |
| Project | **SQL Server** | Core project management entity. Existing implementation. |
| ProjectTask | **SQL Server** | Task tracking. Existing implementation. |
| ProjectTaskSubmission | **SQL Server** | Submission workflow. Existing implementation. |

---

## 6. Source-of-Truth Rules

| Data | Authoritative Store | Notes |
|------|-------------------|-------|
| Factual company profile | MongoDB (`company_profiles`) | Master record |
| Profile version history | MongoDB (`company_profile_versions`) | Immutable snapshots |
| Relationship type and existence | Neo4j (typed edges) | Created on candidate approval |
| Project-scoped relationship type | SQL Server (`projects.target_relationship_type`) | Per-project evaluation scope |
| Contract metadata | NOT YET IMPLEMENTED | Proposed: MongoDB |
| Operational metrics | NOT YET IMPLEMENTED | Proposed: MongoDB |
| Approved role scores | SQL Server (`score_snapshots`) | Immutable, idempotent |
| Draft role evaluations | MongoDB (`role_evaluation_drafts`) | Mutable until approval |
| AI extraction results | MongoDB (`ai_extraction_results`) | Per-document field-level quality |
| External signals | MongoDB (`external_data_items`) | News, market signals |
| Neo4j company nodes | Neo4j (`Company`) | **Projection/cache** of MongoDB CompanyProfile |
| `CompanyNode.name` | Neo4j | Projection of `CompanyProfile.identity.legalName` |
| `CompanyNode.industry` | Neo4j | Projection of first `CompanyProfile.business.industries` entry |

---

## 7. UI Aggregation Example — Partner Detail Page

A Partner Detail page aggregates data from multiple entities. This does NOT mean all data should be embedded in CompanyProfile.

| Tab | Data Source | Entity |
|-----|------------|--------|
| **Overview** | Company identity, business, size | CompanyProfile (MongoDB) |
| **Relationship** | Relationship type, status, creation metadata | Neo4j edge + Project (SQL) |
| **Contracts** | Contract list, terms, status | PartnerContract (MongoDB, future) |
| **Performance** | KPIs, SLAs, delivery metrics | RoleMetricRecord (MongoDB, future) |
| **Documents** | Uploaded files, extraction status | RawDocument + AiExtractionCache (MongoDB) |
| **Evaluations** | Draft scores, approved snapshots | RoleEvaluationDraft (MongoDB) + ScoreSnapshot (SQL) |
| **Audit History** | Change log, approvals | AuditLog (SQL) + CompanyProfileVersion (MongoDB) |

---

## 8. Field Classification Reference

Every field found during the repository audit, classified:

### CompanyProfile fields
| Field Path | Classification |
|------------|----------------|
| `identity.*` | COMPANY_FACT |
| `business.industries` | COMPANY_FACT |
| `business.businessModel` | COMPANY_FACT |
| `business.products[].name` | COMPANY_FACT |
| `business.markets` | COMPANY_FACT |
| `business.targetCustomers` | COMPANY_FACT |
| `companySize.*` | COMPANY_FACT |
| `contact.*` | COMPANY_FACT |
| `financial.*` | COMPANY_FACT |
| `market.*` | COMPANY_FACT |
| `innovation.*` | COMPANY_FACT |
| `risk.legalRisk` | COMPANY_FACT |
| `risk.financialRisk` | COMPANY_FACT |
| `risk.reputationRisk` | COMPANY_FACT |
| `risk.securityRisk` | COMPANY_FACT |
| `risk.conflictOfInterestRisk` | COMPANY_FACT |
| `risk.supplyInterruptionRisk` | COMPANY_FACT |
| `risk.dependencyRisk` | COMPANY_FACT |
| `risk.overallRiskLevel` | COMPANY_FACT (qualitative, may be AI-derived) |
| `compliance.*` | COMPANY_FACT |
| `insights.strengths` | **AI_INSIGHT** |
| `insights.weaknesses` | **AI_INSIGHT** |
| `insights.opportunities` | **AI_INSIGHT** |
| `insights.threats` | **AI_INSIGHT** |

### CompanyCandidate additional fields
| Field Path | Classification |
|------------|----------------|
| `suggestedRelationshipType` | AI_INSIGHT |
| `relationshipConfidenceScore` | AI_INSIGHT |
| `relationshipTypeOverride` | RELATIONSHIP_FACT |
| `relationshipSuggestion.*` | AI_INSIGHT |
| `scorePreview.estimatedTotalScore` | **LEGACY_OR_UNCLEAR** |
| `scorePreview.scoreFactorsSummary` | **LEGACY_OR_UNCLEAR** |
| `validation.*` | Metadata |
| `normalization.*` | Metadata |
| `deduplication.*` | Metadata |
| `extractionSource.*` | Metadata |
| `review.*` | Metadata |
| `aiMetadata.*` | Metadata |

### ScoreSnapshot fields
| Field Path | Classification |
|------------|----------------|
| `partnerFitScore` | LEGACY_OR_UNCLEAR |
| `competitionLevel` | LEGACY_OR_UNCLEAR |
| `riskLevel` | LEGACY_OR_UNCLEAR |
| `relationshipStrength` | LEGACY_OR_UNCLEAR |
| `totalScore` | LEGACY_OR_UNCLEAR |
| `factorsJson` | LEGACY_OR_UNCLEAR |
| `ruleVersion` | LEGACY_OR_UNCLEAR |
| `overallScore` | APPROVED_SCORE_RESULT |
| `criterionScoresJson` | APPROVED_SCORE_RESULT |
| `normalizedCriterionScoresJson` | APPROVED_SCORE_RESULT |
| `weightsUsedJson` | APPROVED_SCORE_RESULT |
| `evaluatedRole` | APPROVED_SCORE_RESULT |
| `completenessStatus` | APPROVED_SCORE_RESULT |

### Neo4j edge properties
| Property | Classification |
|----------|----------------|
| Edge label (PARTNER_WITH, etc.) | RELATIONSHIP_FACT |
| `confidenceScore` | RELATIONSHIP_FACT |
| `confirmedBy` | RELATIONSHIP_FACT |
| `confirmedAt` | RELATIONSHIP_FACT |
| `projectId` | Provenance |
| `candidateId` | Provenance |

### ExternalDataItem fields
| Field Path | Classification |
|------------|----------------|
| `title`, `summary`, `source`, `url` | EXTERNAL_SIGNAL |
| `sentiment`, `riskLevel`, `opportunityLevel` | EXTERNAL_SIGNAL |
| `relatedCompanyName`, `relatedCompanyId` | EXTERNAL_SIGNAL (linkage) |

---

## 9. Missing-Data Policy

- **null means unknown** — not poor, not average.
- AI must be allowed to return **no score** when insufficient data exists.
- Incomplete evidence must be **visible**, not silently hidden.
- `evidenceCoverage` is separate from `confidence`.
- `confidence` does NOT modify the score — it describes the reliability of the evidence.
- A criterion with insufficient required data returns:
  - `score = null`
  - `validationStatus = WARNING` or `FAIL`
  - `reviewStatus = NEEDS_MORE_DATA`
  - `missingData` list populated
- An official `overallScore` must NOT be calculated when required criterion inputs are missing.

---

## 10. AI Quality Pattern

AI scoring follows a quality-control pattern similar to AI extraction:

1. **Evidence** — source field paths, document references
2. **Confidence** — AI's self-assessed reliability
3. **Validation status** — automated checks (range, completeness)
4. **Review status** — human review state
5. **Staff accept/edit/reject** — criterion input from suggestion
6. **Manager approval** — final gate before ScoreSnapshot

**Constraints:**
- Extraction field results must NOT be reused as scoring results.
- Criterion suggestions must NOT be stored in `AiExtractionCache`.
- `ExtractionReviewStatus` must NOT be reused for score semantics.
- AI must NOT write official `ScoreSnapshot` records.
- AI must NOT define AHP weights.
- AI must NOT return `overallScore`.
- AI must NOT fabricate missing metrics.
