# Competitor Criterion Rubric

## Overview

This document specifies the evaluation rubric for the **COMPETITOR** role (`CompanyRole.COMPETITOR`, mapped from `RelationshipType.COMPETITOR_OF`).

All six criteria use **illustrative AHP weights** (`WeightSource.ILLUSTRATIVE`, version `ILLUSTRATIVE_AHP_V1`). Sub-metric weighting within each criterion is **NOT_DEFINED** — expert input is required before official sub-metric weights can be established.

All COMPETITOR criteria use **THREAT** direction — higher scores indicate a greater competitive threat to FPT.

---

## 1. marketPositionScore

| Property | Value |
|----------|-------|
| **Criterion key** | `marketPositionScore` |
| **Top-level AHP weight** | 20% |
| **Score direction** | THREAT |
| **Business definition** | Measures the competitor's relative market position, visibility, and influence in markets where FPT competes. |
| **Comparison type** | Competitor's market strength **versus** FPT's position in shared markets. |

### Factual input fields — FPT (reference company)
| Field | Source Entity | Classification |
|-------|--------------|----------------|
| `market.marketShare` | CompanyProfile (FPT) | COMPANY_FACT |
| `market.brandRank` | CompanyProfile (FPT) | COMPANY_FACT |
| `market.clientCount` | CompanyProfile (FPT) | COMPANY_FACT |
| `market.mainMarkets` | CompanyProfile (FPT) | COMPANY_FACT |
| `business.markets` | CompanyProfile (FPT) | COMPANY_FACT |

### Factual input fields — Competitor (target company)
| Field | Source Entity | Classification |
|-------|--------------|----------------|
| `market.marketShare` | CompanyProfile (competitor) | COMPANY_FACT |
| `market.brandRank` | CompanyProfile (competitor) | COMPANY_FACT |
| `market.clientCount` | CompanyProfile (competitor) | COMPANY_FACT |
| `market.mainMarkets` | CompanyProfile (competitor) | COMPANY_FACT |
| `business.markets` | CompanyProfile (competitor) | COMPANY_FACT |
| `financial.revenue` | CompanyProfile (competitor) | COMPANY_FACT |

### External signal fields
| Field | Source Entity | Classification |
|-------|--------------|----------------|
| market news/signals | ExternalDataItem | EXTERNAL_SIGNAL |

### Required evidence
- Comparative market research
- Market share reports from credible sources
- Brand ranking publications
- Industry analyst reports

### Deterministic calculations
None — market position requires comparative evidence that cannot be reduced to a single formula.

### AI-assisted analysis
AI may analyze market reports and external signals. AI must reference specific evidence sources and must not fabricate market share data.

### Minimum data requirements
At least one of: competitor `market.marketShare`, `market.brandRank`, or evidence-based market position analysis.

### Missing-data behavior
If no comparative market evidence exists: `score = null`, `reviewStatus = NEEDS_MORE_DATA`.

### Score-band descriptions (ILLUSTRATIVE)
| Band | Score Range | Threat Level |
|------|-----------|-------------|
| Critical | 90–100 | Dominant market leader in shared markets |
| High | 70–89 | Strong market presence threatening FPT's position |
| Moderate | 50–69 | Notable competitor with growing influence |
| Low | 25–49 | Minor market presence with limited overlap |
| Minimal | 0–24 | Negligible market position in shared markets |

### Open decisions
- Sub-metric weighting: **NOT_DEFINED** (EXPERT_INPUT_REQUIRED)
- How to normalize market share across markets of different sizes: **EXPERT_INPUT_REQUIRED**
- Geographic vs. segment market position weighting: **EXPERT_INPUT_REQUIRED**

### Implementation status
- Factual data: ✅ Partially available (market.*, business.markets in CompanyProfile)
- External signals: ✅ ExternalDataItem exists but lacks scoring integration
- AI suggestion: ❌ Not implemented
- Manual input: ✅ CriterionInput supports manual entry

---

## 2. productMarketOverlapScore

| Property | Value |
|----------|-------|
| **Criterion key** | `productMarketOverlapScore` |
| **Top-level AHP weight** | 16% |
| **Score direction** | THREAT |
| **Business definition** | Measures the degree of overlap between FPT's and the competitor's product offerings, markets, industries, and target customers. |
| **Comparison type** | FPT product/market portfolio **versus** competitor product/market portfolio. |

### Factual input fields — FPT
| Field | Source Entity | Classification |
|-------|--------------|----------------|
| `business.products[].name` | CompanyProfile (FPT) | COMPANY_FACT |
| `business.products[].category` | CompanyProfile (FPT) | COMPANY_FACT |
| `business.markets` | CompanyProfile (FPT) | COMPANY_FACT |
| `business.industries` | CompanyProfile (FPT) | COMPANY_FACT |
| `business.targetCustomers` | CompanyProfile (FPT) | COMPANY_FACT |

### Factual input fields — Competitor
| Field | Source Entity | Classification |
|-------|--------------|----------------|
| `business.products[].name` | CompanyProfile (competitor) | COMPANY_FACT |
| `business.products[].category` | CompanyProfile (competitor) | COMPANY_FACT |
| `business.markets` | CompanyProfile (competitor) | COMPANY_FACT |
| `business.industries` | CompanyProfile (competitor) | COMPANY_FACT |
| `business.targetCustomers` | CompanyProfile (competitor) | COMPANY_FACT |

### Required evidence
- Product catalogs or service descriptions
- Market coverage documentation
- Industry analyst comparisons

### Method
**HYBRID** (Deterministic engine + AI-assisted layer + Staff review)

### Responsibilities

**Deterministic engine:**
- Normalized Jaccard calculations
- Fixed component weights
- Reproducible score calculations
*(Currently implemented via [CompetitorComparisonService.java](file:///Users/davisiukem/IdeaProjects/apms-backend/src/main/java/com/apms/domain/score/service/CompetitorComparisonService.java))*

Five Jaccard-similarity components with illustrative weights:
- Product name overlap: 30%
- Product category overlap: 10%
- Market overlap: 25%
- Industry overlap: 20%
- Target customer overlap: 15%

**Important:** `Product.description` is NOT used in the overlap calculation. Jaccard similarity uses normalized `Product.name` and `Product.category` values. These component weights are **ILLUSTRATIVE_ONLY**.

**AI-assisted layer:**
- Propose synonym mappings
- Propose taxonomy normalization
- Explain overlap
- Reference supporting evidence
- Report ambiguity and missing data
- *Important*: AI must NOT independently override the deterministic score without an explicit Staff-reviewed mapping or override reason. This criterion must be part of the generic six-criterion automatic suggestion workflow.

**Staff:**
- Review mappings and suggestion
- Accept, edit, or reject

### Minimum data requirements
At least one overlap component must have data for both FPT and the competitor (products, markets, industries, or target customers).

### Missing-data behavior
- Component weights remain fixed.
- Missing values are NOT converted to 0.
- Missing values are NOT converted to 50.
- Available component weights are NOT silently renormalized.
- `evidenceCoverage` is calculated separately.
- Insufficient coverage may produce `score = null`.
- The final minimum coverage threshold is **EXPERT_INPUT_REQUIRED**.

**CURRENT IMPLEMENTATION GAP:** If `CompetitorComparisonService` currently renormalizes available components, this is explicitly logged as an implementation gap to be fixed in future phases.

### Score-band descriptions (ILLUSTRATIVE)
| Band | Score Range | Threat Level |
|------|-----------|-------------|
| Critical | 90–100 | Near-total product/market overlap — direct competitor |
| High | 70–89 | Significant overlap across multiple dimensions |
| Moderate | 50–69 | Moderate overlap in key areas |
| Low | 25–49 | Limited overlap in niche areas |
| Minimal | 0–24 | Negligible product/market overlap |

### Open decisions
- Component weight refinement: **ILLUSTRATIVE_ONLY** (current weights are illustrative)
- Whether to incorporate product description semantic analysis: **EXPERT_INPUT_REQUIRED**

### Implementation status
- Deterministic calculation: ✅ CompetitorComparisonService implements Jaccard similarity
- Automatic suggestion: ✅ `suggestProductMarketOverlap()` generates AutomaticSuggestion
- Staff acceptance: ✅ `acceptAutomaticSuggestion()` converts to CriterionInput
- Manager confirmation: ✅ Required before approval
- Manual override: ✅ Staff may edit the score

---

## 3. competitiveCapabilityScore

| Property | Value |
|----------|-------|
| **Criterion key** | `competitiveCapabilityScore` |
| **Top-level AHP weight** | 20% |
| **Score direction** | THREAT |
| **Business definition** | Measures the competitor's technological, organizational, and operational capabilities that could give them a competitive advantage over FPT. |
| **Comparison type** | Competitor capabilities **versus** FPT capabilities in contested domains. |

### Factual input fields — FPT
| Field | Source Entity | Classification |
|-------|--------------|----------------|
| `innovation.technologyCapabilities` | CompanyProfile (FPT) | COMPANY_FACT |
| `innovation.techStack` | CompanyProfile (FPT) | COMPANY_FACT |
| `innovation.patents` | CompanyProfile (FPT) | COMPANY_FACT |
| `innovation.rdInvestmentPercent` | CompanyProfile (FPT) | COMPANY_FACT |
| `companySize.employeeCount` | CompanyProfile (FPT) | COMPANY_FACT |

### Factual input fields — Competitor
| Field | Source Entity | Classification |
|-------|--------------|----------------|
| `innovation.technologyCapabilities` | CompanyProfile (competitor) | COMPANY_FACT |
| `innovation.techStack` | CompanyProfile (competitor) | COMPANY_FACT |
| `innovation.patents` | CompanyProfile (competitor) | COMPANY_FACT |
| `innovation.rdInvestmentPercent` | CompanyProfile (competitor) | COMPANY_FACT |
| `innovation.techMaturityLevel` | CompanyProfile (competitor) | COMPANY_FACT |
| `innovation.productInnovationRate` | CompanyProfile (competitor) | COMPANY_FACT |
| `companySize.employeeCount` | CompanyProfile (competitor) | COMPANY_FACT |
| `compliance.qualityCertifications` | CompanyProfile (competitor) | COMPANY_FACT |

### Required evidence
- Technology capability assessments
- Patent filings
- R&D investment data
- Technical talent indicators
- Industry certifications

### AI-assisted analysis
AI may compare capability portfolios and identify areas where the competitor has a significant advantage. AI must reference specific capability evidence.

### Minimum data requirements
At least technology capabilities for both companies.

### Missing-data behavior
If competitor or FPT has no innovation/capability data: `score = null`, `reviewStatus = NEEDS_MORE_DATA`.

### Score-band descriptions (ILLUSTRATIVE)
| Band | Score Range | Threat Level |
|------|-----------|-------------|
| Critical | 90–100 | Overwhelming capability advantage over FPT |
| High | 70–89 | Strong capabilities exceeding FPT in key areas |
| Moderate | 50–69 | Competitive capabilities with some advantages |
| Low | 25–49 | Limited capability advantage |
| Minimal | 0–24 | No significant capability threat |

### Open decisions
- Sub-metric weighting: **NOT_DEFINED** (EXPERT_INPUT_REQUIRED)
- How to weight R&D investment vs. patent count vs. tech capabilities: **EXPERT_INPUT_REQUIRED**

### Implementation status
- Factual data: ✅ Partially available in CompanyProfile
- AI suggestion: ❌ Not implemented
- Manual input: ✅ CriterionInput supports manual entry

---

## 4. strategicIntentScore

| Property | Value |
|----------|-------|
| **Criterion key** | `strategicIntentScore` |
| **Top-level AHP weight** | 11% |
| **Score direction** | THREAT |
| **Business definition** | Measures the competitor's strategic direction and whether their plans directly target FPT's markets, customers, or strategic domains. |
| **Comparison type** | Competitor's announced or inferred strategic direction **versus** FPT's strategic territory. |

### Factual input fields — Competitor
| Field | Source Entity | Classification |
|-------|--------------|----------------|
| `business.industries` | CompanyProfile (competitor) | COMPANY_FACT |
| `business.markets` | CompanyProfile (competitor) | COMPANY_FACT |
| `business.targetCustomers` | CompanyProfile (competitor) | COMPANY_FACT |
| `insights.opportunities` | CompanyProfile (competitor) | AI_INSIGHT |

### External signal fields
| Field | Source Entity | Classification |
|-------|--------------|----------------|
| News about expansion, acquisitions, new markets | ExternalDataItem | EXTERNAL_SIGNAL |

### Required evidence
- Competitor press releases, annual reports
- Market expansion announcements
- Acquisition/merger news
- Strategic partnership announcements
- External analyst reports

### AI-assisted analysis
AI may analyze external signals and public statements to infer strategic intent. AI must clearly distinguish between confirmed actions and inferred intentions.

### Minimum data requirements
At least external signal evidence or public strategic information about the competitor.

### Missing-data behavior
If no strategic intent evidence exists: `score = null`, `reviewStatus = NEEDS_MORE_DATA`.

### Score-band descriptions (ILLUSTRATIVE)
| Band | Score Range | Threat Level |
|------|-----------|-------------|
| Critical | 90–100 | Directly and aggressively targeting FPT's core domains |
| High | 70–89 | Clear strategic direction toward FPT's markets |
| Moderate | 50–69 | Some indications of competitive intent |
| Low | 25–49 | Peripheral strategic overlap |
| Minimal | 0–24 | No evidence of intent to compete with FPT |

### Open decisions
- Sub-metric weighting: **NOT_DEFINED** (EXPERT_INPUT_REQUIRED)
- How to weight confirmed actions vs. inferred intentions: **EXPERT_INPUT_REQUIRED**

### Implementation status
- Factual data: ✅ Partially available (business fields)
- External signals: ✅ ExternalDataItem exists but lacks scoring integration
- AI suggestion: ❌ Not implemented
- Manual input: ✅ CriterionInput supports manual entry

---

## 5. growthMomentumScore

| Property | Value |
|----------|-------|
| **Criterion key** | `growthMomentumScore` |
| **Top-level AHP weight** | 9% |
| **Score direction** | THREAT |
| **Business definition** | Measures the competitor's growth trajectory and acceleration. Static values alone do NOT prove growth — period-defined evidence is required. |
| **Comparison type** | Competitor's growth indicators over a defined period. |

### Factual input fields — Competitor
| Field | Source Entity | Classification |
|-------|--------------|----------------|
| `financial.revenue` | CompanyProfile (competitor) | COMPANY_FACT |
| `financial.revenueGrowth` | CompanyProfile (competitor) | COMPANY_FACT |
| `companySize.employeeCount` | CompanyProfile (competitor) | COMPANY_FACT |
| `market.clientCount` | CompanyProfile (competitor) | COMPANY_FACT |
| `innovation.productInnovationRate` | CompanyProfile (competitor) | COMPANY_FACT |

### External signal fields
| Field | Source Entity | Classification |
|-------|--------------|----------------|
| Funding news, expansion announcements | ExternalDataItem | EXTERNAL_SIGNAL |

### Required evidence
- Revenue growth reports with defined time periods
- Employee headcount changes over time
- Market expansion evidence
- Product launch frequency
- Funding rounds or investment announcements

### Important clarification
**Static values alone do NOT prove growth.** A single revenue snapshot without a comparison period is insufficient. Evidence must demonstrate change over time:
- Revenue at T1 vs. revenue at T2
- Employee count change
- Client count change
- New market entries over a period

### AI-assisted analysis
AI may analyze growth trends from financial reports and external signals. AI must require period-defined data and must not infer growth from a single data point.

### Minimum data requirements
At least one period-defined growth indicator (e.g., `financial.revenueGrowth`, or two snapshots of the same metric over time).

### Missing-data behavior
If no period-defined growth evidence exists: `score = null`, `reviewStatus = NEEDS_MORE_DATA`. Static values alone are insufficient.

### Score-band descriptions (ILLUSTRATIVE)
| Band | Score Range | Threat Level |
|------|-----------|-------------|
| Critical | 90–100 | Explosive growth across multiple dimensions |
| High | 70–89 | Strong sustained growth trajectory |
| Moderate | 50–69 | Moderate growth in key areas |
| Low | 25–49 | Slow or inconsistent growth |
| Minimal | 0–24 | Stagnant or declining |

### Open decisions
- Sub-metric weighting: **NOT_DEFINED** (EXPERT_INPUT_REQUIRED)
- Minimum measurement period for growth evidence: **EXPERT_INPUT_REQUIRED**
- How to weight revenue growth vs. headcount growth vs. market expansion: **EXPERT_INPUT_REQUIRED**

### Implementation status
- Factual data: ✅ Partially available (financial.revenueGrowth exists)
- External signals: ✅ ExternalDataItem exists
- AI suggestion: ❌ Not implemented
- Manual input: ✅ CriterionInput supports manual entry

---

## 6. competitiveThreatScore

| Property | Value |
|----------|-------|
| **Criterion key** | `competitiveThreatScore` |
| **Top-level AHP weight** | 24% |
| **Score direction** | THREAT |
| **Business definition** | Measures the direct, FPT-specific competitive threat based on evidence of actual competitive encounters, customer losses, and pricing pressure. |
| **Comparison type** | Direct evidence of competitive impact on FPT. |

### Important constraints
- **Must NOT be derived from the other five criterion scores.** This is an independent assessment.
- **Requires direct FPT-specific threat evidence.** General competitor strength does not equal FPT-specific threat.
- **Missing evidence must produce null, not 0 or 50.**

### Factual input fields
None directly from CompanyProfile — this criterion requires relationship-specific competitive encounter evidence.

### Relationship/external input fields (not yet implemented)
| Field | Source Entity | Classification |
|-------|--------------|----------------|
| competingBids | RoleMetricRecord (future) | EXTERNAL_SIGNAL |
| customersLostToTarget | RoleMetricRecord (future) | EXTERNAL_SIGNAL |
| pricingPressure | RoleMetricRecord (future) | EXTERNAL_SIGNAL |
| expansionSignals | ExternalDataItem | EXTERNAL_SIGNAL |
| productLaunches | ExternalDataItem | EXTERNAL_SIGNAL |

### Required evidence
- Records of head-to-head competitive encounters
- Customer win/loss data specifically involving this competitor
- Pricing pressure evidence in shared markets
- Direct competitive actions targeting FPT's customers

### AI-assisted analysis
AI may analyze competitive encounter records and external signals. AI must not infer FPT-specific threat from general competitor characteristics. AI must not derive this score from the other five criteria.

### Minimum data requirements
At least one piece of direct competitive encounter evidence involving FPT.

### Missing-data behavior
If no FPT-specific threat evidence exists: `score = null`, `reviewStatus = NEEDS_MORE_DATA`. This criterion is explicitly null-safe — no score is better than a fabricated score.

### Score-band descriptions (ILLUSTRATIVE)
| Band | Score Range | Threat Level |
|------|-----------|-------------|
| Critical | 90–100 | Active, direct, and successful competitive attacks on FPT |
| High | 70–89 | Demonstrated wins against FPT in key accounts |
| Moderate | 50–69 | Evidence of competitive encounters with mixed outcomes |
| Low | 25–49 | Indirect competitive pressure |
| Minimal | 0–24 | No evidence of direct competitive threat to FPT |

### Open decisions
- Sub-metric weighting: **NOT_DEFINED** (EXPERT_INPUT_REQUIRED)
- How to weight customer losses vs. pricing pressure vs. bid competition: **EXPERT_INPUT_REQUIRED**

### Implementation status
- Factual data: ❌ No competitive encounter data exists in the repository
- External signals: ✅ ExternalDataItem exists but lacks scoring integration
- AI suggestion: ❌ Not implemented
- Manual input: ✅ CriterionInput supports manual entry

---

## Implementation Gap: Current vs. Intended COMPETITOR Workflow

### Current state (Phase 2B)
- `productMarketOverlapScore`: ✅ Automatic Jaccard-based suggestion via CompetitorComparisonService
- Other 5 criteria: ✅ Manual-only entry via CriterionInput in RoleEvaluationDraft
- AI-assisted suggestions for remaining 5 criteria: ❌ Not implemented

### Intended state (future phases)
- `productMarketOverlapScore`: Jaccard suggestion (existing) + optional AI enhancement
- `marketPositionScore`: AI-assisted suggestion using market data + external signals
- `competitiveCapabilityScore`: AI-assisted suggestion using innovation/capability comparison
- `strategicIntentScore`: AI-assisted suggestion using external signals + public statements
- `growthMomentumScore`: AI-assisted suggestion using financial growth + external signals
- `competitiveThreatScore`: AI-assisted suggestion using competitive encounter evidence

### Gap for six-criterion AI suggestion workflow
1. **Evidence aggregation**: No service currently collects multi-source evidence for a single criterion
2. **AI prompt design**: No scoring-specific AI prompts exist (only extraction prompts)
3. **AI quality model**: No `AiCriterionSuggestion` entity separate from `AutomaticSuggestion`
4. **Confidence/validation**: Current `AutomaticSuggestion` has basic structure but lacks validation/review fields comparable to `ExtractionFieldResult`
5. **External signal integration**: `ExternalDataItem` exists but is not integrated into criterion scoring
6. **Period-defined evidence**: No mechanism to specify evaluation time periods for growth evidence
