# Competitor Scoring Rubric Specification

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

**Phase 2A — Audit and Design Only**
**Document Type:** Rubric Specification
**Version:** 1.0
**Date:** 2026-07-12
**Status:** FINAL — No Runtime Changes

---

## 1. Purpose

This document specifies the proposed criterion-by-criterion rubric for scoring a target company against FPT Corporation as a COMPETITOR. It defines data sources, scoring bands, evidence requirements, missing-data behavior, and workflow requirements for each of the six canonical COMPETITOR criteria.

**This is a design document. No implementation is performed in Phase 2A.**

All scoring calculations — including the example in section 19 — use the Phase 1 `RoleScoringEngine` unchanged.

---

## 2. Role and Direction

- **Evaluated Role:** COMPETITOR
- **Score Direction for all criteria:** THREAT
- **Interpretation of THREAT:** Raw score is used directly as the normalized score.
  - 0 = negligible threat for that criterion
  - 100 = extreme threat for that criterion
  - No inversion is applied (COST inversion is NOT applied to THREAT direction)
- **FPT is the Reference Company.** FPT cannot be the target.
- **Role source:** `Project.targetRelationshipType = COMPETITOR_OF`
- **Role mapping:** verified in `RelationshipTypeToCompanyRoleMapper.COMPETITOR_OF → CompanyRole.COMPETITOR`

---

## 3. Illustrative AHP Weights

| Criterion Key              | Illustrative Weight | Rank |
|----------------------------|--------------------|------|
| competitiveThreatScore     | 0.24               | 1st  |
| marketPositionScore        | 0.20               | 2nd  |
| competitiveCapabilityScore | 0.20               | 2nd  |
| productMarketOverlapScore  | 0.16               | 4th  |
| strategicIntentScore       | 0.11               | 5th  |
| growthMomentumScore        | 0.09               | 6th  |
| **Total**                  | **1.00**           |      |

**WARNING:** These weights are ILLUSTRATIVE and NOT expert-approved. They are proposed rubric coefficients for development purposes only. Do not present them as validated AHP weights.

Weight metadata: weightingMethod = AHP, weightSource = ILLUSTRATIVE, weightVersion = ILLUSTRATIVE_AHP_V1

---

## 4. Criterion Definitions

### marketPositionScore
**Question:** How strong is the target's current market position relative to FPT in relevant markets?
**What it measures:** The target's current external market strength in markets where FPT operates. Higher score = stronger target position = higher threat to FPT.
**What it does NOT measure:** Product similarity, growth trajectory, stated intentions, or direct customer displacement.

### productMarketOverlapScore
**Question:** How strongly do the target and FPT overlap in products, services, industries, customer groups, and markets?
**What it measures:** Structural similarity of what is offered and who is served. Higher overlap = higher threat.
**What it does NOT measure:** Company size, innovation capability, growth rate, or direct threat observations.

### competitiveCapabilityScore
**Question:** How capable is the target of competing effectively against FPT?
**What it measures:** Resources, technology, talent, and innovation capacity to execute competitive actions. Higher capability = higher threat.
**What it does NOT measure:** Product-market overlap itself, current market position, future intent, or direct observed losses.

### strategicIntentScore
**Question:** How strongly does available evidence indicate that the target intends to enter, expand in, or challenge FPT's strategic markets?
**What it measures:** Verified evidence of active competitive intent. Higher verified intent = higher threat.
**What it does NOT measure:** Generic mission statements, company size, existing market position, inferred capability.

### growthMomentumScore
**Question:** How rapidly is the target strengthening its competitive position?
**What it measures:** Verified change rate over a defined period across relevant competitive dimensions. Higher momentum = higher threat.
**What it does NOT measure:** Current company size, one-time values, generic positive news, or intent without measured growth.

### competitiveThreatScore
**Question:** What is the target's direct and near-term threat to FPT not already captured by the other five criteria?
**What it measures:** Direct observed near-term exposure, damage, or displacement risk to FPT specifically.
**What it does NOT measure:** A recombination of the other five criteria — doing so constitutes double-counting.

---

## 5. Criterion Boundary Matrix

### What Belongs in Each Criterion (and What Does Not)

| Criterion | Belongs | Does NOT Belong |
|---|---|---|
| marketPositionScore | Current market share, brand rank, client count, revenue scale where market-relevant, verified position in shared markets | Product/service similarity, growth rate, stated plans, direct FPT customer losses |
| productMarketOverlapScore | Product/service overlap, industry overlap, geographic market overlap, customer-segment overlap | Company size, innovation capability, growth rate, direct customer displacement severity |
| competitiveCapabilityScore | Technology capability, innovation assets, R&D strength, execution scale, talent, financial resources | Product-market overlap itself, current market position ranking, future intent, observed direct customer loss |
| strategicIntentScore | Verified plans and actions indicating expansion or challenge (launches, acquisitions, hiring, partnerships, market-entry announcements, public strategic plans) | Generic mission statements, company size, existing market position, inferred capability without evidence |
| growthMomentumScore | Verified change over a defined period (revenue growth with period, customer growth, workforce growth, market-share increase, expansion velocity) | Current size alone, one-time static values, generic positive news, strategic intent without measured growth |
| competitiveThreatScore | Direct observed near-term exposure to FPT: customer displacement, contract competition, pricing pressure, bid losses, customer switching, direct substitute risk, market-share loss attributable to target | Recombination of the other five criteria, target size alone, public reputation alone, generic capability |

### CRITICAL: No Double-Counting Rule

**competitiveThreatScore MUST NOT be calculated as a weighted or unweighted aggregation of:**
- marketPositionScore
- productMarketOverlapScore
- competitiveCapabilityScore
- strategicIntentScore
- growthMomentumScore

**Reason:** competitiveThreatScore already has its own AHP weight (0.24). Deriving it from the other five criteria would double-count the same information in the overall score.

### Invalid Double-Counting Examples

1. Using market.clientCount in both marketPositionScore AND productMarketOverlapScore.
2. Using innovation.technologyCapabilities as a proxy for marketPositionScore.
3. Using financial.revenueGrowth in both competitiveCapabilityScore AND growthMomentumScore.
4. Calculating competitiveThreatScore as average(other five scores).
5. Using insights.threats free text as evidence for strategicIntentScore or competitiveThreatScore.

---

## 6. marketPositionScore Rubric

**criterionKey:** marketPositionScore
**direction:** THREAT
**weight:** 0.20 (illustrative)
**classification:** HYBRID

### Required Inputs (minimum: >=2 indicators)
| Indicator | Owner Field | Target Field | Java Type |
|---|---|---|---|
| Market share | market.marketShare | market.marketShare | BigDecimal |
| Brand rank | market.brandRank | market.brandRank | Integer |
| Client count | market.clientCount | market.clientCount | Long |
| Revenue scale | companySize.revenueTier | companySize.revenueTier | String |

### Optional Supporting Inputs
- financial.revenue (if available and reviewed)
- companySize.employeeCount (as secondary scale indicator only)

### Evidence Required (HYBRID case)
Each used indicator must have an approved profile value traceable to a reviewed source document.

### Confidence Tiers
| Tier | Condition |
|---|---|
| HIGH_CONFIDENCE | >=3 reliable indicators with reviewed values |
| MEDIUM_CONFIDENCE | 2 reliable indicators |
| LOW_CONFIDENCE | 1 reliable indicator — Manager-reviewed manual input required |
| INSUFFICIENT | 0 reliable indicators — criterion = null |

### Calculation Approach (proposed, not implemented)

**Sub-indicator scoring bands (for each indicator separately):**

Revenue tier (target relative to FPT tier "Large Enterprise"):
- Same or larger tier: sub-score 70–90
- One tier smaller: sub-score 40–70
- Two or more tiers smaller: sub-score 10–40
- Missing: excluded

Client count (relative to FPT clientCount if available, else absolute):
- Target clientCount > FPT: sub-score 75–100
- Within 50% of FPT: sub-score 50–75
- < 50% of FPT: sub-score 20–50
- Missing: excluded

Market share (absolute percentage in shared markets):
- >20%: sub-score 80–100
- 10–20%: sub-score 55–80
- 5–10%: sub-score 30–55
- <5%: sub-score 10–30
- Missing: excluded

Combined score: simple average of available sub-indicator scores.

### Normalization
- All inputs must be from approved profile versions.
- Cross-market comparison requires market scope confirmation.
- Reviewer must confirm the indicators reflect the same market(s) where FPT operates.

### Missing-Data Behavior
- 0 indicators: criterion = null; completenessStatus = INCOMPLETE
- 1 indicator: requires Manager-reviewed manual confirmation
- >=2 indicators: hybrid auto-proposal; Manager confirms or overrides

### Manual Override Policy
- Manual override allowed at any confidence tier.
- Requires: reason, evidence references, previous value, new value, reviewedByAccountId, reviewedAt.

---

## 7. productMarketOverlapScore Rubric

**criterionKey:** productMarketOverlapScore
**direction:** THREAT
**weight:** 0.16 (illustrative)
**classification:** HYBRID

### Component Model

| Component | Internal Weight | Owner Field Path | Target Field Path |
|---|---|---|---|
| Product/service overlap | 40% | business.products (name, reviewed category) | business.products (name, reviewed category) |
| Geographic market overlap | 25% | business.markets | business.markets |
| Industry overlap | 20% | business.industries | business.industries |
| Target-customer overlap | 15% | business.targetCustomers | business.targetCustomers |

**WARNING:** These internal component weights are proposed rubric coefficients only. They are NOT AHP-derived and NOT expert-approved.

### Overlap Calculation Method

For each set-valued component, apply Jaccard similarity:

```
Jaccard(A, B) = |intersection| / |union|
ComponentScore = round(Jaccard × 100)
```

### Normalization Rules
1. Lowercase all string values before comparison.
2. Trim leading and trailing whitespace.
3. Remove duplicate values within each set.
4. Unicode-safe comparison (normalize to NFC form).
5. Preserve original values for evidence display; use normalized values for computation.
6. Exact normalized matching is the default.
7. Synonym or taxonomy matching requires a separate reviewed mapping table — NOT automatic.
8. LLM output MAY suggest synonym mappings but CANNOT silently apply them as official overlap values.

### Zero-Division Rules
- If both FPT set and target set are empty: component = null, NOT 0, NOT 100.
- If only one side is empty AND both profiles are confirmed complete for that field: component = 0.
- If one side is empty and the field completeness is unverified: component = null.

### Minimum Sufficient Input
- REQUIRED: products (at least 1 product with name or category from each side)
- REQUIRED: at least one of: markets, industries, targetCustomers
- If only products are available: score can proceed at LOW_CONFIDENCE with Manager review.

### Missing-Data Behavior
- All components null: criterion = null; completenessStatus = INCOMPLETE
- Products null: criterion = null even if other components are available
- Markets/industries/targetCustomers null: exclude that component; reweight remaining components proportionally (using the internal proportions above) AND generate a coverage warning in the automatic suggestion.

### Evidence Required
- FPT product/market data: from approved FPT profile version
- Target product/market data: from approved target profile version
- Synonym mappings (if any): from reviewed taxonomy mapping table

---

## 8. competitiveCapabilityScore Rubric

**criterionKey:** competitiveCapabilityScore
**direction:** THREAT
**weight:** 0.20 (illustrative)
**classification:** HYBRID (currently blocked until FPT innovation fields are seeded)

### Capability Dimensions

| Dimension | Owner Field | Target Field | Type |
|---|---|---|---|
| Technology capability | innovation.technologyCapabilities | innovation.technologyCapabilities | List<String> |
| Tech maturity | innovation.techMaturityLevel | innovation.techMaturityLevel | Integer |
| Innovation assets | innovation.patents | innovation.patents | Integer |
| R&D investment | innovation.rdInvestmentPercent | innovation.rdInvestmentPercent | BigDecimal |
| Product innovation rate | innovation.productInnovationRate | innovation.productInnovationRate | BigDecimal |
| Execution scale (employee) | companySize.employeeCount | companySize.employeeCount | Integer |
| Execution scale (tier) | companySize.employeeTier | companySize.employeeTier | String |
| Revenue scale | financial.revenue | financial.revenue | BigDecimal |

### Rules
- Shared technologies do NOT automatically mean high competitive capability.
- Scale is a necessary but not sufficient indicator of capability.
- Do NOT include market share indicators (belongs in marketPositionScore).
- Do NOT include revenueGrowth (belongs in growthMomentumScore).
- Do NOT include product-market overlap (belongs in productMarketOverlapScore).

### Minimum Sufficient Input
- REQUIRED: at least 1 technology/innovation indicator (technologyCapabilities OR techStack)
- REQUIRED: at least 1 scale/resource indicator (employeeCount OR employeeTier OR revenueTier)
- Without minimum: criterion = null

### Current Blocker
FPT has no seeded innovation fields. Relative comparison against FPT is impossible until FPT's innovation profile is enriched. In the interim, scoring must use reviewed manual input.

### Scoring Approach (proposed, not implemented)

Technology capability: set-overlap comparison using normalized technologyCapabilities (similar to Jaccard), weighted against recency and breadth of capabilities.

Scale comparison (employee tier):
- Target >= FPT tier (">40,000"): sub-score 80–100
- Target one tier smaller: sub-score 50–80
- Target much smaller: sub-score 20–50

Patents (if available): absolute count bands.

Combined: weighted average of available dimension sub-scores.

### Missing-Data Behavior
- Missing technology indicator: criterion = null
- Missing scale indicator: criterion = null
- Missing R&D or patents: excluded from calculation (not null-forcing)

---

## 9. strategicIntentScore Rubric

**criterionKey:** strategicIntentScore
**direction:** THREAT
**weight:** 0.11 (illustrative)
**classification:** MANUAL_REVIEWED

### Definition
Measures verified evidence that the target intends to enter, expand in, or challenge FPT's strategic markets.

**This criterion must not be fabricated from generic company descriptions.**

### Evidence Categories

| Category | Description |
|---|---|
| MARKET_ENTRY | Announced entry into a market where FPT operates |
| PRODUCT_LAUNCH | Launch of a product/service competing with FPT's offerings |
| ACQUISITION | Acquisition targeting an overlapping segment or capability |
| MAJOR_INVESTMENT | Material investment in overlapping capabilities |
| CAPACITY_EXPANSION | Hiring or capacity expansion targeting overlapping domains |
| STRATEGIC_PARTNERSHIP | Partnership aimed at FPT's customer segments |
| PUBLIC_STRATEGIC_PLAN | Official public strategy document targeting overlapping areas |
| INTERNAL_INTELLIGENCE | Verified internal competitive business intelligence |

### Reliability Levels

| Level | Examples |
|---|---|
| HIGH | Official regulatory filing, official company announcement, verified contract or BD intelligence |
| MEDIUM | Reputable industry publication, corroborated press coverage |
| LOW | Unverified article, unsupported claim, AI inference |

**LOW-only evidence MUST NOT produce an official score without explicit Manager confirmation.**

### Evidence Requirements
Each evidence item must include:
- evidenceCategory (from above)
- sourceType (EXTERNAL_EVIDENCE, INTERNAL_OPERATIONAL_DATA, USER_REVIEWED_INPUT)
- rawDocumentId or URL
- evidenceDate (must be within relevance window)
- reliabilityLevel (HIGH, MEDIUM, LOW)
- reviewedByAccountId
- reviewedAt
- note

### Evidence Recency Policy
- Evidence >24 months old: reduced weight in scoring rationale
- Evidence >36 months old: must be explicitly confirmed as still current by Manager
- Evidence with no date: LOW reliability by default

### Scoring Bands

| Band | Score Range | Condition |
|---|---|---|
| No intent evidence | NULL | No verified evidence collected |
| Distant intent | 1–20 | Only very distant or non-overlapping exploratory activity |
| Limited activity | 21–40 | Limited or weak exploratory signals |
| Moderate expansion | 41–60 | Clear preparation or moderate expansion into overlapping areas |
| Strong expansion | 61–80 | Strong verified expansion into FPT's strategic areas |
| Active challenge | 81–100 | Explicit, active, near-term challenge to FPT priority markets |

### Missing-Data Behavior
- No evidence: criterion = null
- Do NOT assign score = 0 merely because evidence was not collected.

### AI Prohibition
- AI confidence scores are NOT criterion scores.
- AI may assist in identifying evidence but cannot assign the official strategicIntentScore value.
- All AI-suggested evidence requires human review and Manager approval.

---

## 10. growthMomentumScore Rubric

**criterionKey:** growthMomentumScore
**direction:** THREAT
**weight:** 0.09 (illustrative)
**classification:** MANUAL_REVIEWED (current state; HYBRID when time-series data becomes available)

### Potential Dimensions

| Dimension | Source Field | Period Required | Currently Available |
|---|---|---|---|
| Revenue growth | financial.revenueGrowth | YES — unknown in current model | Partial (no period) |
| Customer/client growth | market.clientCount delta | YES | NO — single version only |
| Workforce growth | companySize.employeeCount delta | YES | NO — employeeCount rarely in extraction |
| Market-share growth | market.marketShare delta | YES | NO — rarely populated |
| Product/market expansion | business.markets/industries delta | YES | NO — needs version review |

### Blocking Conditions
1. `financial.revenueGrowth` has no time period defined in the data model — cannot be used for growth calculation without period.
2. Profile-version deltas require Manager review to distinguish data corrections from real business changes.
3. Only 1 seeded version exists for FPT and most targets — no historical baseline.

### Requirements for Auto-Calculation (future HYBRID state)
- Time period must be explicitly documented (e.g., "FY2024 vs FY2023").
- Units must be consistent (percentage or absolute).
- Source period must be confirmed as comparable (like-for-like).
- Both endpoints of the time series must come from reviewed sources.

### Negative Growth
- Negative growth reduces the threat score for that dimension.
- A company with declining revenue is less of a growth threat but may still be a current-position threat (captured in marketPositionScore).

### Scoring Bands (illustrative — for future use when period data is available)

Revenue growth rate (if period is known):
- >30% YoY: sub-score 80–100 (very high momentum)
- 15–30% YoY: sub-score 60–80
- 5–15% YoY: sub-score 35–60
- 0–5% YoY: sub-score 15–35
- Negative: sub-score 0–15 (declining momentum)
- Unknown period: sub-score cannot be calculated

### Missing-Data Behavior
- No verified period-defined metric: criterion = null
- Single static value without period: criterion = null
- Do NOT generate a neutral score of 50 for missing growth data.

---

## 11. competitiveThreatScore Rubric

**criterionKey:** competitiveThreatScore
**direction:** THREAT
**weight:** 0.24 (illustrative — highest weight)
**classification:** MANUAL_REVIEWED

### Definition
Measures the target's direct and near-term threat to FPT that is NOT already represented by the other five criteria.

### CRITICAL: Not an Aggregation of Other Criteria
competitiveThreatScore MUST NOT be calculated as any form of aggregation of:
- marketPositionScore
- productMarketOverlapScore
- competitiveCapabilityScore
- strategicIntentScore
- growthMomentumScore

Doing so constitutes double-counting.

### Distinct Input Sources

| Evidence Type | Description | Source Type |
|---|---|---|
| Customer displacement | Verified FPT customer lost to target | INTERNAL_OPERATIONAL_DATA |
| Contract competition | Active or recent competing bid | INTERNAL_OPERATIONAL_DATA |
| Pricing pressure | Target price undercut in same deal | INTERNAL_OPERATIONAL_DATA |
| Market-share loss | FPT market-share loss attributable to target | INTERNAL_OPERATIONAL_DATA |
| Customer switching | FPT customer switching to target | INTERNAL_OPERATIONAL_DATA |
| Priority-segment win | Target win in FPT priority segment | INTERNAL_OPERATIONAL_DATA |
| Direct substitute adoption | Target product adopted as FPT substitute | EXTERNAL_EVIDENCE or INTERNAL |
| Contract displacement | Verified contract displacement | INTERNAL_OPERATIONAL_DATA |

### Transparent Scoring Model (proposed)

```
threatScore = likelihoodScore × severityScore / 100
```
Then apply time-proximity factor if separately documented.

Do not finalize this formula until Phase 2B implementation.

### Likelihood and Severity Components

Likelihood (0–100): How probable is direct competitive impact in the next 12 months?
Severity (0–100): How severe would the impact be for FPT?

### Time Horizon
- Near-term: within 12 months — full weight
- Medium-term: 12–36 months — reduced but still scored
- Long-term: >36 months — captures in strategicIntentScore instead

### Scoring Bands

| Band | Score Range | Condition |
|---|---|---|
| No direct threat | NULL | No direct-threat evidence |
| Remote threat | 1–20 | Remote or minor direct threat |
| Limited exposure | 21–40 | Limited exposure with low severity |
| Moderate threat | 41–60 | Credible moderate threat with some direct evidence |
| High threat | 61–80 | High direct threat with meaningful impact evidence |
| Severe exposure | 81–100 | Immediate, severe competitive exposure to FPT |

### Missing-Data Behavior
- No direct evidence: criterion = null
- Do NOT infer this score from public profile size alone.
- Do NOT derive this score from other criterion scores.

### Evidence Requirements
- Same structure as strategicIntentScore evidence.
- Must include severity and likelihood components separately.
- Must include time horizon documentation.
- Must include reviewedByAccountId and reviewedAt.

---

## 12. Missing-Data Policy

### Canonical Rules

- **null is not zero.** A null criterion score represents unknown, not "no threat."
- **unknown is not neutral.** Do not substitute 50 for a missing criterion.
- **absence of evidence is not evidence of absence.**
- A null required criterion causes:
  - `completenessStatus = INCOMPLETE`
  - `overallScore = null`
  - `missingCriteria` contains the null criterion key

### Per-Criterion Policy

| Criterion | Minimum Sufficient Input | When Auto-Calc Allowed | When Manual Input Required | When Null |
|---|---|---|---|---|
| marketPositionScore | >=2 of: marketShare, brandRank, clientCount, revenueTier | >=2 reviewed indicators available | 1 indicator only | 0 indicators |
| productMarketOverlapScore | products + one other component | All required components have reviewed values | Synonym/taxonomy mapping needed | Products missing from either side |
| competitiveCapabilityScore | 1 tech indicator + 1 scale indicator | Both reviewed indicator types available | Only 1 dimension type available | Either tech or scale indicator missing |
| strategicIntentScore | >=1 verified evidence record | Never auto-calculated | Always | No evidence |
| growthMomentumScore | 1 period-defined growth metric | Only when period is explicitly known and reviewed | Always (current state) | No period-defined metric |
| competitiveThreatScore | >=1 verified direct-threat evidence record | Never auto-calculated | Always | No direct evidence |

---

## 13. Evidence Requirements

### Evidence Record Structure

Each criterion should support zero or more evidence records. Each record must include:

| Field | Type | Required | Notes |
|---|---|---|---|
| evidenceId | String | YES | Unique ID |
| criterionKey | String | YES | e.g., "strategicIntentScore" |
| sourceType | String | YES | OWNER_PROFILE, TARGET_PROFILE, INTERNAL_OPERATIONAL_DATA, USER_REVIEWED_INPUT, EXTERNAL_EVIDENCE |
| rawDocumentId | String | No | MongoDB raw document ID if from document |
| companyProfileId | String | No | Profile source |
| profileVersion | Integer | No | Profile version provenance |
| externalUrl | String | No | URL of external source |
| evidenceDate | LocalDate | YES | Date of evidence or publication |
| extractedFieldPath | String | No | e.g., "innovation.technologyCapabilities" |
| evidenceCategory | String | YES | From defined category list |
| reliabilityLevel | String | YES | HIGH, MEDIUM, LOW |
| preparedByAccountId | Long | YES | Staff who entered the evidence |
| preparedAt | LocalDateTime | YES | |
| reviewedByAccountId | Long | No | Manager who confirmed |
| reviewedAt | LocalDateTime | No | |
| note | String | No | Explanation of evidence relevance |

### Prohibited Evidence Sources
- Raw LLM output as direct evidence (AI may suggest; Manager must confirm)
- Unreviewed AI-extracted values
- insights.strengths/weaknesses/threats free text fields (not structured evidence)

---

## 14. Manual Override Policy

### Manual Score Entry Rules
- Allowed range: 0–100 inclusive.
- Required fields when entering a manual score:
  - criterionKey
  - rawScore (0–100)
  - explanation (why this score)
  - evidenceRefs (at least one evidence record)
  - evidenceDate
  - preparedByAccountId
  - preparedAt
  - (for overrides) overrideReason
  - (for overrides) previousValue
  - (for overrides) newValue

### Forbidden Client Inputs
The following fields must NEVER be accepted from the client:
- overallScore
- normalizedCriterionScores
- weightsUsed
- weightVersion override
- ruleSetVersion override
- Any criterion weight

### Audit Log Requirements
Every manual score change must generate an immutable audit event:
- timestamp
- criterionKey
- previousValue
- newValue
- changedByAccountId
- reason

---

## 15. Draft Workflow

### RoleEvaluationDraft Architecture

**Recommendation:** Generic `RoleEvaluationDraft` (MongoDB), reusable across all five roles.

**Reason for recommending generic over role-specific:**
- All five roles (PARTNER, POTENTIAL_PARTNER, COMPETITOR, CUSTOMER, SUPPLIER) share the same lifecycle.
- JSON criterion input storage is naturally generic with `criterionInputsJson`.
- A role-specific `CompetitorEvaluationDraft` would need to be duplicated five times.
- The generic draft uses the canonical criterion key as the identifier for all per-criterion data.

### Proposed Draft Fields

| Field | Type | Notes |
|---|---|---|
| id | String | MongoDB ID |
| projectId | Long | Links to SQL Project |
| taskId | Long | Links to SQL ProjectTask |
| targetCompanyProfileId | String | Fixed at draft creation |
| targetProfileVersion | Integer | Fixed at draft creation |
| referenceCompanyProfileId | String | FPT company ID; fixed |
| referenceProfileVersion | Integer | Fixed at draft creation |
| evaluatedRole | CompanyRole | COMPETITOR |
| ruleSetVersion | String | ROLE_SCORING_V1 |
| weightVersion | String | ILLUSTRATIVE_AHP_V1 |
| criterionInputsJson | String | Per-criterion raw scores and explanations |
| criterionEvidenceJson | String | Per-criterion evidence records |
| automaticSuggestionsJson | String | System-proposed values (not official) |
| reviewStatus | RoleEvaluationStatus | DRAFT, SUBMITTED, IN_REVIEW, APPROVED, REJECTED, REVISION_REQUIRED |
| staleTargetProfile | Boolean | True when newer target profile exists |
| staleReferenceProfile | Boolean | True when newer FPT profile exists |
| staleRuleSet | Boolean | True when newer rule set exists |
| submittedBy | Long | Account ID |
| submittedAt | LocalDateTime | |
| reviewedBy | Long | Account ID (Manager) |
| reviewedAt | LocalDateTime | |
| reviewComment | String | |
| createdAt | LocalDateTime | |
| updatedAt | LocalDateTime | |

### Draft Status Transitions

```
DRAFT → SUBMITTED → IN_REVIEW → APPROVED → (triggers snapshot creation)
                             → REVISION_REQUIRED → DRAFT
                 → REJECTED
```

### Why CompanyProfileUpdateProposal is NOT Suitable
- `CompanyProfileUpdateProposal` changes factual profile data.
- `RoleEvaluationDraft` evaluates approved factual data using criterion inputs.
- These have incompatible lifecycles and semantics.
- Conflating them would corrupt the separation between profile management and scoring.

---

## 16. Manager Approval

### Approval Preconditions
Before Manager can approve a COMPETITOR evaluation:
1. `Project.targetRelationshipType = COMPETITOR_OF`
2. Target CompanyProfile exists with `reviewStatus = "APPROVED"`
3. Target CompanyProfileVersion snapshot exists for the draft's `targetProfileVersion`
4. FPT CompanyProfile exists with `reviewStatus = "APPROVED"`
5. FPT CompanyProfileVersion snapshot exists for the draft's `referenceProfileVersion`
6. All required criterion inputs are present and reviewed
7. No prohibited fields were submitted by the client

### On Manager Approval
1. Backend validates all preconditions.
2. Backend calls `RoleScoringEngine.calculate()` with the approved criterion inputs.
3. `CanonicalScoreSnapshotService.createCanonicalSnapshot()` creates an immutable row.
4. Draft status is set to APPROVED.
5. Prior snapshots are not modified.

### Scoring Authority Summary

| Role | Can Prepare Inputs | Can Submit Draft | Can Approve | Can Create Snapshot |
|---|---|---|---|---|
| BUSINESS_DEVELOPMENT_STAFF | YES | YES | NO | NO |
| BUSINESS_DEVELOPMENT_MANAGER | Can edit inputs | Can submit | YES | Triggered by approval |
| BUSINESS_OWNER | View only | NO | NO | NO |
| SYSTEM_ADMIN | Technical access | NO | NO | NO (unless explicit permission policy grants it) |

---

## 17. Version-Staleness Behavior

### Version Binding
A `RoleEvaluationDraft` binds immutably to:
- `targetProfileVersion` — the approved version at draft creation
- `referenceProfileVersion` — FPT's approved version at draft creation
- `ruleSetVersion` — the active rule set at draft creation
- `weightVersion` — the weight version at draft creation

### Staleness Detection
| Event | Effect |
|---|---|
| Target profile updated and new version approved | `staleTargetProfile = true`; draft remains valid but flagged |
| FPT profile updated and new version approved | `staleReferenceProfile = true`; draft remains valid but flagged |
| Rule set version changes | `staleRuleSet = true` |
| Evidence record date exceeds recency threshold | Flagged in evidence review |

### Stale Approval Behavior
- Manager must explicitly confirm stale-version approval or create a new draft against the newer version.
- Approval of a stale draft is allowed if the Manager explicitly acknowledges it.
- The stale flag is recorded in the approval audit log.

### Approved Snapshot Immutability
- Approved `ScoreSnapshot` rows are never modified after creation.
- Never overwrite prior snapshots.
- New evaluations create new snapshots.
- The latest snapshot is identified by `calculatedAt` descending.
- Never average multiple historical snapshots.

---

## 18. API Proposal

**Design only — not implemented in Phase 2A.**

### Endpoint List

```
POST   /api/v1/projects/{projectId}/tasks/{taskId}/role-evaluations
GET    /api/v1/projects/{projectId}/tasks/{taskId}/role-evaluations
GET    /api/v1/role-evaluations/{evaluationId}
PATCH  /api/v1/role-evaluations/{evaluationId}/criteria/{criterionKey}
POST   /api/v1/role-evaluations/{evaluationId}/evidence
POST   /api/v1/role-evaluations/{evaluationId}/calculate-preview
POST   /api/v1/role-evaluations/{evaluationId}/submit
POST   /api/v1/role-evaluations/{evaluationId}/review
GET    /api/v1/profiles/{companyProfileId}/role-scores?role=COMPETITOR
```

### Endpoint Details

**POST /api/v1/projects/{projectId}/tasks/{taskId}/role-evaluations**
- Actor: STAFF
- Purpose: Create a new RoleEvaluationDraft bound to the current profile versions
- Precondition: Project.targetRelationshipType = COMPETITOR_OF; both profiles approved
- Request: Empty body (evaluatedRole is derived from Project.targetRelationshipType)
- Response: { evaluationId, status: "DRAFT", evaluatedRole: "COMPETITOR", targetProfileVersion, referenceProfileVersion, ... }
- Idempotency: One active draft per project/task/role; returns existing draft if DRAFT status

**GET /api/v1/projects/{projectId}/tasks/{taskId}/role-evaluations**
- Actor: STAFF, MANAGER
- Purpose: List evaluations for this task
- Response: Array of evaluation summaries

**GET /api/v1/role-evaluations/{evaluationId}**
- Actor: STAFF, MANAGER
- Purpose: Full draft details including criterion inputs and evidence
- Includes: stale flags, reviewStatus, evidence records

**PATCH /api/v1/role-evaluations/{evaluationId}/criteria/{criterionKey}**
- Actor: STAFF
- Purpose: Submit or update a criterion input
- Request: { rawScore: 0–100, explanation: "...", evidenceRefs: [...] }
- Forbidden request fields: overallScore, normalizedCriterionScores, weightsUsed
- State constraint: Only allowed when reviewStatus = DRAFT or REVISION_REQUIRED

**POST /api/v1/role-evaluations/{evaluationId}/evidence**
- Actor: STAFF
- Purpose: Add an evidence record to a criterion
- Request: { criterionKey, sourceType, evidenceCategory, reliabilityLevel, evidenceDate, note, ... }

**POST /api/v1/role-evaluations/{evaluationId}/calculate-preview**
- Actor: STAFF, MANAGER
- Purpose: Preview the weighted score using current criterion inputs (not stored as official snapshot)
- Returns: { previewOverallScore, completenessStatus, missingCriteria } — labeled as PREVIEW
- Does not create a ScoreSnapshot

**POST /api/v1/role-evaluations/{evaluationId}/submit**
- Actor: STAFF
- Purpose: Submit the draft for Manager review
- Changes draft reviewStatus to SUBMITTED; task status to IN_REVIEW

**POST /api/v1/role-evaluations/{evaluationId}/review**
- Actor: MANAGER
- Purpose: Approve or reject the draft
- Request: { decision: "APPROVED" | "REJECTED" | "REVISION_REQUIRED", comment: "..." }
- On APPROVED: calls RoleScoringEngine, creates immutable ScoreSnapshot in SQL server via idempotent operation, sets draft to APPROVED in MongoDB
- On REJECTED or REVISION_REQUIRED: sets status accordingly; does not create snapshot

**GET /api/v1/profiles/{companyProfileId}/role-scores?role=COMPETITOR**
- Actor: MANAGER, BUSINESS_OWNER, STAFF
- Purpose: List all canonical ScoreSnapshot rows for this profile and role
- Returns snapshots ordered by calculatedAt descending
- Latest snapshot is the current approved score

### Forbidden Client Fields in All Endpoints
- overallScore
- normalizedCriterionScores
- weightsUsed
- weightVersion
- ruleSetVersion (must be derived from the active rule set)
- Any AHP weight value

---

## 19. Example: Complete COMPETITOR Evaluation (ILLUSTRATIVE)

**ILLUSTRATIVE EXAMPLE ONLY — All values are fictional reviewed inputs, not real company data.**

| Field | Value |
|---|---|
| target | Fictional Competitor X (illustrative) |
| evaluatedRole | COMPETITOR |
| ruleSetVersion | ROLE_SCORING_V1 |
| weightVersion | ILLUSTRATIVE_AHP_V1 |
| completenessStatus | COMPLETE |

### Illustrative Reviewed Criterion Inputs

| Criterion | Raw Score | Direction | Normalized Score | Weight | Weighted Contribution |
|---|---|---|---|---|---|
| marketPositionScore | 65 | THREAT | 65 | 0.20 | 13.00 |
| productMarketOverlapScore | 80 | THREAT | 80 | 0.16 | 12.80 |
| competitiveCapabilityScore | 70 | THREAT | 70 | 0.20 | 14.00 |
| strategicIntentScore | 55 | THREAT | 55 | 0.11 | 6.05 |
| growthMomentumScore | 40 | THREAT | 40 | 0.09 | 3.60 |
| competitiveThreatScore | 75 | THREAT | 75 | 0.24 | 18.00 |

**overallScore = 13.00 + 12.80 + 14.00 + 6.05 + 3.60 + 18.00 = 67.45**

**Formula:** overallScore = Σ(normalizedCriterionScore × illustrativeAHPWeight) = 67.45 (rounded to 2 decimal places, RoundingMode.HALF_UP)

**Note:** THREAT direction does not invert scores. Normalized score = raw score.

**Interpretation:** This illustrative competitor scores 67.45/100 on the COMPETITOR threat scale. The highest contributors are competitiveThreatScore (direct operational evidence, weight 0.24) and competitiveCapabilityScore/marketPositionScore (both weight 0.20).

---

## 20. Example: Incomplete COMPETITOR Evaluation

**ILLUSTRATIVE EXAMPLE ONLY — Demonstrates null-criterion behavior.**

| Criterion | Raw Score | Notes |
|---|---|---|
| marketPositionScore | 65 | Reviewed — COMPLETE |
| productMarketOverlapScore | 80 | Reviewed — COMPLETE |
| competitiveCapabilityScore | null | No technology indicators available |
| strategicIntentScore | null | No evidence collected |
| growthMomentumScore | null | No period-defined metric |
| competitiveThreatScore | null | No direct-threat evidence |

**Result:**
- completenessStatus = INCOMPLETE
- overallScore = null
- missingCriteria = ["competitiveCapabilityScore", "strategicIntentScore", "growthMomentumScore", "competitiveThreatScore"]

The RoleScoringEngine (Phase 1, unchanged) correctly produces null for overallScore when any required criterion is missing.

---

## 21. Explicit Non-Goals

The following are explicitly out of scope for Phase 2A and Phase 2B:

1. **Do not** modify `CompanyRole` enum.
2. **Do not** rename, merge, add, or remove any of the six COMPETITOR criterion keys.
3. **Do not** change the Phase 1 rule set weights or add new weights without a new ruleSetVersion.
4. **Do not** implement COMPETITOR rubric logic in Phase 2A (this phase is audit and design only).
5. **Do not** allow the `CandidateApprovedEvent` to trigger canonical COMPETITOR scoring.
6. **Do not** expose a public endpoint that accepts `overallScore`, `normalizedCriterionScores`, or `weightsUsed`.
7. **Do not** use `CompanyProfileUpdateProposal` as a scoring draft.
8. **Do not** use AI LLM output directly as official evidence or as criterion scores.
9. **Do not** calculate `competitiveThreatScore` as an aggregation of the other five COMPETITOR criteria.
10. **Do not** assign score = 0 when evidence is absent (null must remain null).
11. **Do not** assign score = 50 as a "neutral" value for missing criteria.
12. **Do not** claim the illustrative AHP weights are expert-approved.
13. **Do not** use insights.strengths, insights.weaknesses, or insights.threats as structured evidence for scoring.
14. **Do not** automatically trust profile-version deltas as verified growth data.
15. **Do not** modify SQL schema during Phase 2A.
16. **Do not** calculate official scores or create canonical snapshots during Phase 2A.

---

## 22. Phase 2B Recommendation

**Do not implement during Phase 2A.**

Phase 2B implementation should include, in suggested implementation order:

1. New enum values: `TaskType.ROLE_EVALUATION`, `SubmissionType.ROLE_EVALUATION`
2. `RoleEvaluationDraft` MongoDB document with embedded criterion inputs and evidence records
3. `RoleEvaluationDraftRepository` and `RoleEvaluationDraftService`
4. COMPETITOR relationship validation service (Project.targetRelationshipType = COMPETITOR_OF)
5. `CompetitorComparisonService` — automatic Jaccard similarity helpers for productMarketOverlapScore
6. Scale comparison helpers for marketPositionScore and competitiveCapabilityScore
7. Stale-version detection logic
8. Manual evidence entry for strategicIntentScore and competitiveThreatScore
9. Draft state machine (DRAFT → SUBMITTED → IN_REVIEW → APPROVED/REJECTED/REVISION_REQUIRED)
10. ProjectTaskSubmission linkage with SubmissionType.ROLE_EVALUATION
11. Manager review and approval endpoint
12. RoleScoringEngine integration (preview and final calculation — unchanged from Phase 1)
13. CanonicalScoreSnapshotService integration (immutable snapshot creation — unchanged from Phase 1)
14. Read APIs for approved role scores
15. Audit log for criterion score changes
16. Unit tests for all COMPETITOR comparison helpers
17. Unit tests for draft lifecycle state transitions
18. Integration tests for Manager approval flow creating ScoreSnapshot
19. Update documentation: `docs/canonical-role-scoring-api.md`, `docs/canonical-role-scoring-infrastructure.md`

---

*Document generated as part of Phase 2A — COMPETITOR Scoring Data Audit and Rubric Specification.*
*No runtime scoring behavior was changed during this phase.*
