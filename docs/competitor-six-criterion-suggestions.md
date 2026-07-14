# Competitor Six-Criterion Suggestions

## Overview
The Competitor evaluation model generates suggestions for six specific criteria during Role Evaluations. This document details the exact methodology, inputs, and behavior of the suggestion engine.

## Criteria Classification
1. **productMarketOverlapScore**: DETERMINISTIC (AI is explicitly bypassed, relying purely on algorithmic comparison between Reference and Target snapshot overlaps).
2. **marketPositionScore**: AI_ASSISTED
3. **competitiveCapabilityScore**: AI_ASSISTED
4. **strategicIntentScore**: AI_ASSISTED
5. **growthMomentumScore**: AI_ASSISTED
6. **competitiveThreatScore**: AI_ASSISTED

## Exact Input Fields Per Criterion (Context Mapping)
The `CompetitorCriterionEvidenceService` filters and provides strict subsets of `CompanyProfile` snapshots to avoid exposing sensitive data (e.g., contacts, addresses, internal insights) to the AI provider.

- **productMarketOverlapScore**: Uses full profile (handled via `CompetitorComparisonService`).
- **marketPositionScore**: Requires `targetFacts` and `referenceFacts` containing the "markets" key.
- **competitiveCapabilityScore**: Requires `targetFacts` containing either "products" or "services".
- **strategicIntentScore**: Requires `externalSignals` to be non-empty.
- **growthMomentumScore**: Requires `periodStart`, `periodEnd`, and `externalSignals` to be non-empty.
- **competitiveThreatScore**: Requires `draftEvidence` to be non-empty.

## ExternalDataCategory Mapping
The `externalSignals` leverage specific `ExternalDataCategory` values mapped as follows:
- `strategicIntentScore` relies heavily on `COMPANY_NEWS`, `FINANCIAL_REPORT`, and `STRATEGIC_ANNOUNCEMENT`.
- `growthMomentumScore` utilizes `COMPANY_NEWS`, `HIRING_TRENDS`, and `MARKET_REPORT` over the specified `periodStart` to `periodEnd`.

## Pinned Profile Version Behavior
The suggestion engine rigidly adheres to the **pinned versions** of profiles (`referenceProfileVersion`, `targetProfileVersion`) saved to the `RoleEvaluationDraft`. It never substitutes the "latest" version. If the pinned version is missing, or the `companyId` does not match the pinned document, the service throws a `BusinessValidationException`.

## Validation and Failure Modes
- **Technical Failure**: Returned if the API is down, or if the AI output violates the strict JSON allowlist.
- **Missing Business Evidence**: Returned as `NEEDS_MORE_DATA` if the `preconditionsMet()` check fails (e.g., missing "markets" data for `marketPositionScore`), or if the AI itself cannot confidently score based on the provided context.

## Regeneration Protection
An AI suggestion is protected from regeneration if its `reviewStatus` is `ACCEPTED` or `EDITED`. To override this protection:
1. The request must set `force = true`.
2. A non-blank `reviewComment` must be provided.
Manual overrides (`MANUAL_OVERRIDE` input) are always preserved and take precedence in the final calculation.

## Partial Batch Behavior
When `/suggestions/generate` is called:
- The batch processes all six criteria independently.
- If one criterion fails (e.g., technical failure or missing data), it does **not** abort the entire batch.
- The outcome map will reflect `GENERATED`, `TECHNICAL_FAILURE`, or `NEEDS_MORE_DATA` for each specific criterion.
- Neither an `overallScore` nor `CriterionInput` is created during suggestion generation. The user must manually accept/edit suggestions before they become formalized inputs.

## Implementation Rules & Semantics (Phase 2C.6)

1. **Growth Evidence Requirements**: `growthMomentumScore` generates a suggestion only if `periodStart` is strictly before `periodEnd`, and there is either a concrete numeric growth fact (`revenueGrowth`) or an external signal that mentions growth, expansion, or funding along with a numeric value. Static generic values (like `employeeCount`) alone return `NEEDS_MORE_DATA`.

2. **Structured Threat Evidence**: `competitiveThreatScore` relies on explicitly structured direct threat evidence in `EvidenceRecord` (fields: `evidenceId`, `eventType`, `referenceCompanyId`, `targetCompanyId`, `directCompetitiveClaim`, `source`, `evidenceDate`). An event is valid only if it involves both the reference and target companies directly.

3. **External Category Mapping**:
   - `marketPositionScore`: `NEWS`, `MARKET_SIGNAL`
   - `competitiveCapabilityScore`: `NEWS`
   - `strategicIntentScore`: `NEWS`, `MARKET_SIGNAL`
   - `growthMomentumScore`: `NEWS`, `OPPORTUNITY`
   - `competitiveThreatScore`: `RISK`, `MARKET_SIGNAL`
   Note: Criterion relevance filtering occurs strictly *after* this category mapping.

4. **Mongo Backward Compatibility**: New explicit fields in `EvidenceRecord` (e.g., `eventType`, `targetCompanyId`) are fully nullable and backward-compatible. Missing fields in older Mongo documents naturally deserialize to `null` without breaking the application.

5. **Legacy vs Canonical Overlap Routing**:
   - The legacy `POST /api/v1/role-evaluations/{evaluationId}/product-market-overlap/suggest` uses `OverlapSuggestionMode.LEGACY_PARTIAL`. Missing dimensions simply contribute 0 to the un-normalized sum without penalty or renormalization.
   - The canonical `POST /api/v1/role-evaluations/{evaluationId}/criteria/productMarketOverlapScore/suggest` uses `OverlapSuggestionMode.CANONICAL_STRICT`. Missing dimensions return `NEEDS_MORE_DATA` with a `WARNING` status.

6. **Scoring Boundaries**:
   - There is **no AI-generated overallScore**.
   - Staff review and Manager approval remain strictly required for moving a Draft to an approved formal evaluation.
