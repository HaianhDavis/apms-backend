# Partner Criterion Rubric

## Overview

This document specifies the evaluation rubric for the **PARTNER** role (`CompanyRole.PARTNER`, mapped from `RelationshipType.PARTNER_WITH`).

All six criteria use **illustrative AHP weights** (`WeightSource.ILLUSTRATIVE`, version `ILLUSTRATIVE_AHP_V1`). Sub-metric weighting within each criterion is **NOT_DEFINED** — expert input is required before official sub-metric weights can be established.

---

## 1. businessValueContributionScore

| Property | Value |
|----------|-------|
| **Criterion key** | `businessValueContributionScore` |
| **Top-level AHP weight** | 25% |
| **Score direction** | BENEFIT |
| **Business definition** | Measures the tangible and intangible value the partner delivers to FPT relative to contractual objectives and agreed expectations. |
| **Comparison type** | Contract target or agreed objective **versus** realized value delivered to FPT. |

### Contextual input fields (company profile)
| Field | Source Entity | Classification |
|-------|--------------|----------------|
| `financial.revenue` | CompanyProfile (partner) | COMPANY_FACT (Context only) |
| `financial.profitMargin` | CompanyProfile (partner) | COMPANY_FACT (Context only) |

**Important distinction:** Partner total company revenue is not the same as value contributed to FPT. The main comparison is: agreed target / expected value for FPT versus realized value delivered to FPT.

### Relationship input fields (not yet implemented)
| Field | Source Entity | Classification |
|-------|--------------|----------------|
| contractValue | PartnerContract (future) | CONTRACT_DATA |
| revenueTarget | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| realizedRevenue | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| profitTarget | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| realizedProfit | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| costSavingTarget | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| realizedCostSaving | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| newCustomerTarget | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| newCustomersGenerated | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| newMarketTarget | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| newMarketsAccessed | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| nonFinancialValueEvidence | EvidenceRecord | Evidence |

### Required evidence
- Contract or agreement defining targets
- Financial reports showing realized values
- Business case documents with expected vs. actual outcomes

### Deterministic calculations
None officially defined. Possible illustrative formulas:

```
revenueAchievementRate = realizedRevenue / revenueTarget * 100  (ILLUSTRATIVE_ONLY)
profitAchievementRate = realizedProfit / profitTarget * 100     (ILLUSTRATIVE_ONLY)
```

**Note:** FPT total revenue is NOT compared directly with Partner total revenue. The comparison is between the partner's **contracted targets with FPT** and the **realized results**.

### AI-assisted analysis
AI may suggest a score based on available evidence but must:
- Reference specific contractual targets
- Not fabricate missing metrics
- Return null if insufficient evidence exists

### Minimum data requirements
- **Required dimensions:** At least one contractual target-vs-realized pair (revenue, profit, cost saving, or specific non-financial objective).
- **Optional dimensions:** Additional value metrics beyond core contract requirements.
- **Minimum evidence coverage:** **EXPERT_INPUT_REQUIRED**
- A single contextual metric (like partner's total revenue) cannot be used to represent this criterion.

### Missing-data behavior
If critical required dimensions (target-vs-realized data) are missing:
- `score = null`
- `validationStatus = WARNING` or `FAIL`
- `reviewStatus = NEEDS_MORE_DATA`
- `missingData` must list the absent dimensions

### Score-band descriptions (ILLUSTRATIVE)
| Band | Score Range | Description |
|------|-----------|-------------|
| Exceptional | 90–100 | Significantly exceeds all agreed targets |
| Strong | 70–89 | Meets or moderately exceeds most targets |
| Adequate | 50–69 | Meets minimum expectations with some gaps |
| Weak | 25–49 | Falls short of most targets |
| Poor | 0–24 | Fails to deliver meaningful value |

### Open decisions
- Sub-metric weighting: **NOT_DEFINED** (EXPERT_INPUT_REQUIRED)
- How to weight financial vs. non-financial value: **EXPERT_INPUT_REQUIRED**
- Period over which to measure (quarterly, annual): **EXPERT_INPUT_REQUIRED**

### Implementation status
- Entity infrastructure: ❌ No PartnerContract or RoleMetricRecord entities exist
- Data input: ❌ No contract-target or operational-metric fields exist
- AI suggestion: ❌ Not implemented
- Manual input via RoleEvaluationDraft: ✅ CriterionInput supports manual entry

---

## 2. strategicAlignmentScore

| Property | Value |
|----------|-------|
| **Criterion key** | `strategicAlignmentScore` |
| **Top-level AHP weight** | 21% |
| **Score direction** | BENEFIT |
| **Business definition** | Measures how well the partner's strategy, capabilities, and focus areas align with FPT's strategic direction and priorities. |
| **Comparison type** | FPT strategy and priorities **versus** partner profile, joint goals, and joint roadmap. |

### FPT strategic context (reference)
| Field | Source Entity | Classification |
|-------|--------------|----------------|
| `priorityIndustries` | OwnerStrategyContext (conceptual) | STRATEGIC_CONTEXT |
| `priorityMarkets` | OwnerStrategyContext (conceptual) | STRATEGIC_CONTEXT |
| `priorityProducts` | OwnerStrategyContext (conceptual) | STRATEGIC_CONTEXT |
| `priorityCustomerSegments` | OwnerStrategyContext (conceptual) | STRATEGIC_CONTEXT |
| `strategicGoals` | OwnerStrategyContext / ProjectEvaluationContext (conceptual) | STRATEGIC_CONTEXT |

**Important distinction:** `CompanyProfile` similarity alone does not prove strategic alignment. Alignment is evaluated against FPT's `OwnerStrategyContext` and specific `ProjectEvaluationContext`.

### Factual input fields — Partner (target company)
| Field | Source Entity | Classification |
|-------|--------------|----------------|
| `business.industries` | CompanyProfile (partner) | COMPANY_FACT |
| `business.products` | CompanyProfile (partner) | COMPANY_FACT |
| `business.markets` | CompanyProfile (partner) | COMPANY_FACT |
| `business.targetCustomers` | CompanyProfile (partner) | COMPANY_FACT |
| `innovation.technologyCapabilities` | CompanyProfile (partner) | COMPANY_FACT |

### Relationship input fields (not yet implemented)
| Field | Source Entity | Classification |
|-------|--------------|----------------|
| jointGoals | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| jointGoalsCompleted | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| jointRoadmap | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| sharedMarkets | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| sharedProductAreas | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| priorityIndustries (FPT) | OwnerStrategyContext (conceptual) | STRATEGIC_CONTEXT |
| priorityMarkets (FPT) | OwnerStrategyContext (conceptual) | STRATEGIC_CONTEXT |
| strategicGoals (FPT) | OwnerStrategyContext (conceptual) | STRATEGIC_CONTEXT |

### Required evidence
- FPT strategic plan or priorities document
- Joint business plans or roadmaps
- Market/industry overlap analysis

### Deterministic calculations
Possible Jaccard-style overlap for industries, markets, and products (similar to COMPETITOR productMarketOverlap). However, sub-metric weighting is NOT_DEFINED.

### AI-assisted analysis
AI may analyze narrative alignment between FPT's strategic goals and the partner's capabilities. AI must not fabricate joint goals or roadmaps.

### Minimum data requirements
- **Required dimensions:** FPT `OwnerStrategyContext` priorities AND partner core business fields (`business.industries`, `business.products`, `business.markets`).
- **Optional dimensions:** Explicit joint roadmaps or joint goals.
- **Minimum evidence coverage:** **EXPERT_INPUT_REQUIRED**
- `CompanyProfile` similarity alone does not prove strategic alignment.

### Missing-data behavior
If critical required dimensions (FPT strategic context or partner core fields) are empty:
- `score = null`
- `validationStatus = WARNING` or `FAIL`
- `reviewStatus = NEEDS_MORE_DATA`
- `missingData` must list the absent dimensions

### Score-band descriptions (ILLUSTRATIVE)
| Band | Score Range | Description |
|------|-----------|-------------|
| Exceptional | 90–100 | Deep strategic alignment with shared vision |
| Strong | 70–89 | Good alignment on most strategic dimensions |
| Adequate | 50–69 | Partial alignment with some divergence |
| Weak | 25–49 | Limited strategic overlap |
| Poor | 0–24 | Minimal or no strategic alignment |

### Open decisions
- Sub-metric weighting for overlap components: **NOT_DEFINED**
- How to weight profile overlap vs. joint-goal progress: **EXPERT_INPUT_REQUIRED**
- FPT strategic priorities configuration: **NOT_DEFINED** (not yet modeled)

### Implementation status
- Factual profile data: ✅ Available in CompanyProfile
- Relationship data (joint goals): ❌ Not implemented
- FPT priorities: ❌ Not separately configured
- AI suggestion: ❌ Not implemented
- Manual input: ✅ CriterionInput supports manual entry

---

## 3. operationalPerformanceScore

| Property | Value |
|----------|-------|
| **Criterion key** | `operationalPerformanceScore` |
| **Top-level AHP weight** | 20% |
| **Score direction** | BENEFIT |
| **Business definition** | Measures the partner's actual operational execution quality against contractual KPIs, SLAs, and milestone requirements. |
| **Comparison type** | Contract KPI/SLA/milestone requirements **versus** actual partner performance. |

### Factual input fields
None from CompanyProfile — this criterion is entirely relationship-specific.

### Relationship input fields (not yet implemented)
| Field | Source Entity | Classification |
|-------|--------------|----------------|
| kpiCompleted | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| kpiTotal | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| slaCompliant | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| slaTotal | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| deliverablesOnTime | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| deliverablesTotal | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| deliverablesAccepted | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| incidentCount | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| criticalIncidentCount | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| averageResponseTime | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| requiredResponseTime | PartnerContract (future) | CONTRACT_DATA |
| averageResolutionTime | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| requiredResolutionTime | PartnerContract (future) | CONTRACT_DATA |

### Required evidence
- Contract KPI definitions
- SLA agreements
- Performance reports or dashboards
- Incident logs

### Deterministic calculations (ILLUSTRATIVE_ONLY)
```
kpiCompletionRate = kpiCompleted / kpiTotal * 100
slaComplianceRate = slaCompliant / slaTotal * 100
onTimeDeliveryRate = deliverablesOnTime / deliverablesTotal * 100
```

These are illustrative sub-metric formulas. They do NOT establish official sub-metric weights.

### AI-assisted analysis
AI may analyze performance reports and incident logs. AI must not fabricate KPI/SLA numbers.

### Minimum data requirements
- **Required dimensions:** Must include both KPI or deliverable achievement AND SLA, delivery, quality, or service performance.
- **Optional dimensions:** Incident resolution times, optional deliverables.
- **Minimum evidence coverage:** **EXPERT_INPUT_REQUIRED**
- A single KPI value alone is insufficient for a complete criterion score.

### Missing-data behavior
If critical required dimensions (e.g., SLA or deliverable performance) are missing:
- `score = null`
- `validationStatus = WARNING` or `FAIL`
- `reviewStatus = NEEDS_MORE_DATA`
- `missingData` must list the absent dimensions

### Score-band descriptions (ILLUSTRATIVE)
| Band | Score Range | Description |
|------|-----------|-------------|
| Exceptional | 90–100 | Exceeds all KPIs and SLAs consistently |
| Strong | 70–89 | Meets most KPIs/SLAs with minor gaps |
| Adequate | 50–69 | Meets minimum contractual requirements |
| Weak | 25–49 | Frequent KPI/SLA misses |
| Poor | 0–24 | Persistent operational failure |

### Open decisions
- Sub-metric weighting (KPI vs SLA vs delivery): **NOT_DEFINED** (EXPERT_INPUT_REQUIRED)
- Incident severity weighting: **NOT_DEFINED**
- Measurement period: **EXPERT_INPUT_REQUIRED**

### Implementation status
- Data infrastructure: ❌ No RoleMetricRecord or PartnerContract entities
- Manual input: ✅ CriterionInput supports manual entry

---

## 4. capabilityComplementarityScore

| Property | Value |
|----------|-------|
| **Criterion key** | `capabilityComplementarityScore` |
| **Top-level AHP weight** | 16% |
| **Score direction** | BENEFIT |
| **Business definition** | Measures how well the partner's capabilities fill FPT's gaps and enhance FPT's overall capacity. Similar capabilities do NOT automatically mean high complementarity. |
| **Comparison type** | FPT capability gaps and project needs **versus** partner capabilities and actual contribution. |

### FPT capability context (reference)
| Field | Source Entity | Classification |
|-------|--------------|----------------|
| `requiredCapabilities` | ProjectEvaluationContext (conceptual) | PROJECT_CONTEXT |
| `capabilityGaps` | ProjectEvaluationContext (conceptual) | PROJECT_CONTEXT |
| `requiredResources` | ProjectEvaluationContext (conceptual) | PROJECT_CONTEXT |
| `desiredMarketAccess` | ProjectEvaluationContext (conceptual) | PROJECT_CONTEXT |

**Important distinction:** Capability overlap alone does not prove complementarity. Complementarity requires filling FPT's explicit gaps/needs.

### Factual input fields — Partner
| Field | Source Entity | Classification |
|-------|--------------|----------------|
| `innovation.technologyCapabilities` | CompanyProfile (partner) | COMPANY_FACT |
| `innovation.techStack` | CompanyProfile (partner) | COMPANY_FACT |
| `companySize.employeeCount` | CompanyProfile (partner) | COMPANY_FACT |
| `business.products` | CompanyProfile (partner) | COMPANY_FACT |
| `business.markets` | CompanyProfile (partner) | COMPANY_FACT |
| `compliance.qualityCertifications` | CompanyProfile (partner) | COMPANY_FACT |

### Relationship input fields (not yet implemented)
| Field | Source Entity | Classification |
|-------|--------------|----------------|
| requiredCapabilities | ProjectEvaluationContext (conceptual) | PROJECT_CONTEXT |
| capabilityGaps | ProjectEvaluationContext (conceptual) | PROJECT_CONTEXT |
| contributedCapabilities | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| capabilityGapsFilled | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| expertsAssigned | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| technologyAssetsShared | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| marketAccessProvided | RoleMetricRecord (future) | OPERATIONAL_METRIC |

### Important clarification
Similar capabilities do NOT automatically mean high complementarity. A partner that has the exact same tech stack as FPT may provide no complementary value. Complementarity is about **filling gaps**, not duplicating strengths.

### Required evidence
- FPT capability gap analysis
- Partner capability inventory
- Evidence of actual contribution (experts, technology, market access)

### AI-assisted analysis
AI may compare capability lists and identify gaps/overlaps. AI must distinguish between "overlap" (duplication) and "complementarity" (gap-filling).

### Minimum data requirements
- **Required dimensions:** FPT `ProjectEvaluationContext` capability gaps/needs AND partner capabilities/contributions.
- **Optional dimensions:** Specific resource assignments.
- **Minimum evidence coverage:** **EXPERT_INPUT_REQUIRED**
- Capability overlap alone does not prove complementarity.

### Missing-data behavior
If critical required dimensions (FPT capability gaps or partner capabilities) are missing:
- `score = null`
- `validationStatus = WARNING` or `FAIL`
- `reviewStatus = NEEDS_MORE_DATA`
- `missingData` must list the absent dimensions

### Score-band descriptions (ILLUSTRATIVE)
| Band | Score Range | Description |
|------|-----------|-------------|
| Exceptional | 90–100 | Fills critical capability gaps with proven delivery |
| Strong | 70–89 | Provides significant complementary capabilities |
| Adequate | 50–69 | Some useful capabilities with moderate gaps |
| Weak | 25–49 | Limited complementary value |
| Poor | 0–24 | No meaningful capability contribution |

### Open decisions
- How to weight gap-filling vs. capacity amplification: **NOT_DEFINED**
- Sub-metric weighting: **EXPERT_INPUT_REQUIRED**

### Implementation status
- Factual data: ✅ Partially available in CompanyProfile
- Relationship data: ❌ Not implemented
- AI suggestion: ❌ Not implemented
- Manual input: ✅ CriterionInput supports manual entry

---

## 5. relationshipQualityScore

| Property | Value |
|----------|-------|
| **Criterion key** | `relationshipQualityScore` |
| **Top-level AHP weight** | 9% |
| **Score direction** | BENEFIT |
| **Business definition** | Measures the quality of the actual working relationship and interaction between FPT and the partner. |
| **Comparison type** | Actual interaction quality between FPT and partner. |

### Factual input fields
None from CompanyProfile — this criterion is entirely relationship-specific.

### Relationship input fields (not yet implemented)
| Field | Source Entity | Classification |
|-------|--------------|----------------|
| meetingsPlanned | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| meetingsHeld | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| issuesRaised | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| issuesResolved | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| averageIssueResolutionTime | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| escalationCount | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| complaintCount | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| fptSatisfactionScore | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| partnerSatisfactionScore | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| renewalCount | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| communicationQuality | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| informationSharingLevel | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| commitmentLevel | RoleMetricRecord (future) | OPERATIONAL_METRIC |

### Required evidence
- Meeting records
- Issue tracking logs
- Satisfaction surveys
- Communication records

### AI-assisted analysis
AI may analyze meeting notes, issue resolution patterns, and satisfaction survey results. AI must not fabricate relationship quality metrics.

### Minimum data requirements
- **Required dimensions:** Multiple interaction vectors (e.g., meetings, issue resolution, structured feedback).
- **Optional dimensions:** Formal satisfaction scores.
- **Minimum evidence coverage:** **EXPERT_INPUT_REQUIRED**
- A single meeting count or one satisfaction value alone is insufficient for a complete criterion score.

### Missing-data behavior
If critical required dimensions (multiple interaction vectors) are missing:
- `score = null`
- `validationStatus = WARNING` or `FAIL`
- `reviewStatus = NEEDS_MORE_DATA`
- `missingData` must list the absent dimensions

### Score-band descriptions (ILLUSTRATIVE)
| Band | Score Range | Description |
|------|-----------|-------------|
| Exceptional | 90–100 | Outstanding collaboration and mutual commitment |
| Strong | 70–89 | Healthy working relationship with good communication |
| Adequate | 50–69 | Functional relationship with some friction |
| Weak | 25–49 | Strained relationship with frequent issues |
| Poor | 0–24 | Dysfunctional relationship |

### Open decisions
- Sub-metric weighting: **NOT_DEFINED** (EXPERT_INPUT_REQUIRED)
- Qualitative vs. quantitative measurement balance: **EXPERT_INPUT_REQUIRED**

### Implementation status
- Data infrastructure: ❌ No interaction metrics exist
- Manual input: ✅ CriterionInput supports manual entry

---

## 6. governanceComplianceScore

| Property | Value |
|----------|-------|
| **Criterion key** | `governanceComplianceScore` |
| **Top-level AHP weight** | 9% |
| **Score direction** | BENEFIT |
| **Business definition** | Measures the partner's adherence to FPT's contractual, legal, security, and regulatory requirements. |
| **Comparison type** | FPT contractual/legal/security requirements **versus** partner compliance profile and actual incidents. |

### Factual input fields — FPT requirements (not yet separately configured)
| Field | Source Entity | Classification |
|-------|--------------|----------------|
| requiredCertifications | ProjectEvaluationContext (conceptual) | PROJECT_CONTEXT |
| requiredSecurityStandards | ProjectEvaluationContext (conceptual) | PROJECT_CONTEXT |
| contractualObligations | PartnerContract (future) | CONTRACT_DATA |
| dataProtectionRequirements | ProjectEvaluationContext (conceptual) | PROJECT_CONTEXT |
| auditRequirements | ProjectEvaluationContext (conceptual) | PROJECT_CONTEXT |

### Factual input fields — Partner profile
| Field | Source Entity | Classification |
|-------|--------------|----------------|
| `compliance.status` | CompanyProfile (partner) | COMPANY_FACT |
| `compliance.qualityCertifications` | CompanyProfile (partner) | COMPANY_FACT |
| `compliance.securityCertifications` | CompanyProfile (partner) | COMPANY_FACT |
| `risk.legalRisk` | CompanyProfile (partner) | COMPANY_FACT |
| `risk.financialRisk` | CompanyProfile (partner) | COMPANY_FACT |
| `risk.reputationRisk` | CompanyProfile (partner) | COMPANY_FACT |
| `risk.securityRisk` | CompanyProfile (partner) | COMPANY_FACT |

### Relationship input fields (not yet implemented)
| Field | Source Entity | Classification |
|-------|--------------|----------------|
| obligationsTotal | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| obligationsCompliant | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| violationCount | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| unresolvedViolationCount | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| legalDisputeCount | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| securityIncidentCount | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| dataBreachCount | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| auditStatus | RoleMetricRecord (future) | OPERATIONAL_METRIC |
| auditDate | RoleMetricRecord (future) | OPERATIONAL_METRIC |

### Required evidence
- Certification documents
- Audit reports
- Compliance assessment records
- Incident reports

### AI-assisted analysis
AI may analyze compliance documentation and cross-reference with required standards. AI must not fabricate compliance status or incident counts.

### Minimum data requirements
- **Required dimensions:** Both audit/compliance verification AND incident tracking.
- **Optional dimensions:** Advanced security certifications.
- **Minimum evidence coverage:** **EXPERT_INPUT_REQUIRED**
- A certification alone does not prove complete governance and compliance performance.

### Missing-data behavior
If critical required dimensions are missing (e.g., no incident tracking data or no audit data):
- `score = null`
- `validationStatus = WARNING` or `FAIL`
- `reviewStatus = NEEDS_MORE_DATA`
- `missingData` must list the absent dimensions

### Score-band descriptions (ILLUSTRATIVE)
| Band | Score Range | Description |
|------|-----------|-------------|
| Exceptional | 90–100 | Exceeds all compliance requirements, proactive governance |
| Strong | 70–89 | Fully compliant with minor observations |
| Adequate | 50–69 | Meets basic requirements with some gaps |
| Weak | 25–49 | Material compliance gaps or unresolved issues |
| Poor | 0–24 | Critical compliance failures or unresolved violations |

### Open decisions
- Sub-metric weighting: **NOT_DEFINED** (EXPERT_INPUT_REQUIRED)
- Severity weighting for violations: **EXPERT_INPUT_REQUIRED**
- Whether certifications alone qualify for minimum score: **EXPERT_INPUT_REQUIRED**

### Implementation status
- Factual data: ✅ Partially available (compliance.*, risk.* in CompanyProfile)
- FPT requirements config: ❌ Not separately modeled
- Relationship metrics: ❌ Not implemented
- AI suggestion: ❌ Not implemented
- Manual input: ✅ CriterionInput supports manual entry
