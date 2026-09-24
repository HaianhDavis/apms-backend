# Canonical Role-Scoring Infrastructure (Phase 1)

## Overview

Phase 1 introduces a backward-compatible canonical scoring infrastructure that supports independent role evaluations, role-specific criterion rules, versioned illustrative AHP weights, criterion direction normalization, completeness handling, immutable canonical snapshots, and FPT reference-profile provenance — all side-by-side with the existing legacy scoring system.

## Legacy vs Canonical Architecture

### Legacy Scoring (Preserved)
- `ScoreRule` entity: global condition-based rules, unused by actual calculations.
- `ScoreSnapshot` flat fields: `partnerFitScore`, `competitionLevel`, `riskLevel`, `relationshipStrength`, `totalScore`.
- `ScoreService.handleCandidateApprovedEvent()` creates mocked legacy snapshots.
- `ScoreController` exposes `GET /api/v1/profiles/{companyId}/scores`.
- Reports, dashboards, and AI Assistant consume legacy fields.
- **No legacy fields are deleted, renamed, or overwritten.**

### Canonical Scoring (New)
- `RoleScoreRuleSet` and `RoleCriterionRule` entities: versioned, role-specific criterion configurations.
- `ScoreSnapshot` extended with canonical nullable fields.
- `RoleScoringEngine` computes normalized weighted totals.
- `CanonicalScoreSnapshotService` orchestrates validation and persistence.
- `RoleScoreController` exposes `GET /api/v1/profiles/{companyProfileId}/role-scores`.
- Canonical snapshots are distinguished by `evaluatedRole != null`.

### Row Classification Convention
- `evaluatedRole == null` → **legacy snapshot** (consumed by existing reports/dashboards/assistant).
- `evaluatedRole != null` → **canonical role snapshot** (consumed by new canonical endpoints only).

---

## CompanyRole Enum

Created at `com.apms.domain.company.enums.CompanyRole` because no existing `CompanyRole` enum was found in the repository.

Values: `PARTNER`, `POTENTIAL_PARTNER`, `COMPETITOR`, `CUSTOMER`, `SUPPLIER`.

**No `OWNER` value.** The Owner Organization is not a scoreable role.

---

## Canonical Enums (`com.apms.domain.score.enums`)

| Enum | Values | Purpose |
|---|---|---|
| `ScoreDirection` | `BENEFIT`, `COST`, `THREAT` | Criterion normalization direction |
| `WeightingMethod` | `AHP` | Weighting algorithm identifier |
| `WeightSource` | `ILLUSTRATIVE`, `EXPERT` | Provenance of weight values |
| `EvaluationCompletenessStatus` | `INCOMPLETE`, `COMPLETE` | Whether all required criteria were provided |

---

## Rule-Set Entities

### `RoleScoreRuleSet` (table: `role_score_rule_sets`)
- Groups criterion rules for one role and version.
- Unique constraint: `(evaluated_role, rule_set_version)`.
- Once used by a snapshot, treated as immutable configuration history.

### `RoleCriterionRule` (table: `role_criterion_rules`)
- Belongs to one `RoleScoreRuleSet`.
- Unique constraint: `(rule_set_id, criterion_key)`.
- Weight stored as `DECIMAL(8,6)`.
- `ruleDefinitionJson` is nullable (no rubrics in Phase 1).

---

## Criterion Registry (`CanonicalRoleCriteria`)

Centralized, immutable registry containing exactly 5 roles × 6 criteria = 30 canonical criterion keys. Criterion strings are never scattered across services.

### Criteria and Directions

**PARTNER** (all BENEFIT):
`businessValueContributionScore`, `strategicAlignmentScore`, `operationalPerformanceScore`, `capabilityComplementarityScore`, `relationshipQualityScore`, `governanceComplianceScore`

**POTENTIAL_PARTNER** (BENEFIT except `partnershipRiskScore` = COST):
`strategicFitScore`, `capabilityComplementarityScore`, `trustReputationScore`, `financialAttractivenessScore`, `collaborationPotentialScore`, `partnershipRiskScore`

**COMPETITOR** (all THREAT):
`marketPositionScore`, `productMarketOverlapScore`, `competitiveCapabilityScore`, `strategicIntentScore`, `growthMomentumScore`, `competitiveThreatScore`

**CUSTOMER** (BENEFIT except `paymentChurnRiskScore` = COST):
`revenueProfitabilityScore`, `purchaseBehaviorScore`, `customerLifetimeValueScore`, `retentionLoyaltyScore`, `growthPotentialScore`, `paymentChurnRiskScore`

**SUPPLIER** (all BENEFIT — including `supplyRiskComplianceScore`):
`qualityPerformanceScore`, `costCompetitivenessScore`, `deliveryPerformanceScore`, `capacityFlexibilityScore`, `serviceResponsivenessScore`, `supplyRiskComplianceScore`

> **Important:** `supplyRiskComplianceScore` is BENEFIT. A high score means lower supply risk and better compliance.

---

## Illustrative AHP Seed

Seeded by `RoleScoringSeedService` at application startup (controlled by `apms.scoring.seed-illustrative-rules`).

**Metadata:**
- `ruleSetVersion` = `ROLE_SCORING_V1`
- `weightingMethod` = `AHP`
- `weightSource` = `ILLUSTRATIVE`
- `weightVersion` = `ILLUSTRATIVE_AHP_V1`
- `active` = `true`

**Idempotency:** If a rule set for a given role and version already exists, the seeder skips it. It never overwrites historical rule sets.

**Weight sums:** Validated at seed time. Each role's weights must sum to exactly `1.00` (BigDecimal comparison).

**Current weights are illustrative, not expert-approved.** Expert AHP pairwise-comparison management is Phase 7.

---

## Snapshot JSON Structure

### `criterionScoresJson` / `normalizedCriterionScoresJson`
```json
{
  "marketPositionScore": 78,
  "productMarketOverlapScore": null,
  "competitiveCapabilityScore": 85,
  "strategicIntentScore": 70,
  "growthMomentumScore": 82,
  "competitiveThreatScore": 88
}
```

### `weightsUsedJson`
```json
{
  "marketPositionScore": 0.20,
  "productMarketOverlapScore": 0.16,
  "competitiveCapabilityScore": 0.20,
  "strategicIntentScore": 0.11,
  "growthMomentumScore": 0.09,
  "competitiveThreatScore": 0.24
}
```

### `missingCriteriaJson`
```json
["productMarketOverlapScore"]
```

**Rules:** BigDecimal precision preserved. `null` stays `null`. Never serialized as zero. Uses `LinkedHashMap` to preserve canonical registry order.

---

## Normalization Rules

| Direction | Formula | Meaning |
|---|---|---|
| `BENEFIT` | `normalizedScore = rawScore` | Higher is better |
| `COST` | `normalizedScore = 100 - rawScore` | Higher raw score is worse |
| `THREAT` | `normalizedScore = rawScore` | Higher means greater competitive threat |

### Inversions
- `partnershipRiskScore` (POTENTIAL_PARTNER): COST → inverted.
- `paymentChurnRiskScore` (CUSTOMER): COST → inverted.
- `supplyRiskComplianceScore` (SUPPLIER): BENEFIT → **not inverted**.

---

## Completeness Behavior

- If any **required** criterion is missing (null): `completenessStatus = INCOMPLETE`, `overallScore = null`, `missingCriteria` populated.
- If all required criteria present: `completenessStatus = COMPLETE`, `overallScore = sum(normalizedScore × weight)` rounded to 2 decimal places (HALF_UP).
- Null is never treated as zero.
- Missing required criteria are not exceptions — they produce an INCOMPLETE result.

---

## Immutable Canonical Snapshots

- A new `ScoreSnapshot` row is created for each evaluation.
- Prior canonical snapshots are never updated or deleted.
- A target company may have independent snapshots for different roles (PARTNER and COMPETITOR are independent).
- Overall scores from different roles are never averaged or merged.

---

## Profile-Version Provenance

Each canonical snapshot records:
- `targetCompanyProfileId` + `targetProfileVersion`
- `referenceCompanyProfileId` + `referenceProfileVersion` (must be FPT)
- `scoreRuleSetVersion` + `weightVersion` + `weightingMethod` + `weightSource`

---

## CandidateApprovedEvent Behavior

**Legacy behavior preserved:** `ScoreService.handleCandidateApprovedEvent()` continues to create mocked legacy snapshots with `evaluatedRole = null`.

**Canonical scoring is NOT triggered** by `CandidateApprovedEvent` in Phase 1. There are no criterion inputs to evaluate, and ScorePreview is not copied into canonical criteria.

---

## Report / Dashboard / Assistant Integration

**Deferred** until a role rubric is implemented. All legacy consumers now explicitly query `evaluatedRole IS NULL` to avoid accidentally loading canonical rows with null legacy score fields.

---

## No Criterion Rubric

Phase 1 does not implement criterion-specific rubrics, scoring formulas, or automatic data extraction. The `RoleScoringEngine` is a generic mathematical engine that accepts pre-computed criterion scores and applies normalization + weighted aggregation.
