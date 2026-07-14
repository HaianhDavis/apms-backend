# Scoring Suggestion Quality Model

## Overview
The Scoring Suggestion Quality Model governs how AI-assisted evaluation outputs are validated, tracked, and protected during generation to ensure absolute data integrity.

## Strict AI Output Allowlist
To prevent AI hallucinations, prompt injection, and data poisoning, the `GeminiCriterionSuggestionProvider` enforces a rigid JSON allowlist upon the received response.
The parsed JSON must exclusively map to:
- `criterionKey` (String)
- `suggestedRawScore` (BigDecimal)
- `explanation` (String)
- `missingData` (List<String>)

Any presence of the following forbidden fields results in an immediate parsing exception and categorizes the outcome as a `TECHNICAL_FAILURE`:
- `overallScore`
- `weights`
- `ahpWeights`
- `normalizedScores`
- `evaluatedRole`
- `ruleSetVersion`
- `weightVersion`
- `managerConfirmed`
- *Any arbitrary unknown field*

## Business vs. Technical Failures
- **Technical Failure (`TECHNICAL_FAILURE`)**: Denotes structural API errors, timeout failures, missing explanations, out-of-bounds scores (not between 0-100), or allowlist violations. Requires engineering intervention or a manual retry.
- **Business Evidence Failure (`NEEDS_MORE_DATA`)**: Denotes that the AI processed the request but found the context insufficient to confidently score, or that `CompetitorCriterionEvidenceService.preconditionsMet()` actively rejected the context payload prior to reaching the AI.

## Regeneration Protection
AI suggestions are inherently mutable while `PENDING`. However, once a Staff member reviews them:
- An `ACCEPTED` or `EDITED` suggestion is protected.
- Subsequent calls to `/suggestions/generate` or `/criteria/{key}/suggest` will skip the criterion, returning `PROTECTED_FROM_OVERWRITE`.
- A Staff member can override this by supplying a `GenerateSuggestionRequest` with `force = true` **and** a non-blank `reviewComment` explaining the regeneration rationale. Invalid `force` attempts (missing the comment) will be rejected.

## Legacy Deterministic Engine
The `productMarketOverlapScore` is shielded from this quality model because it bypasses the AI provider entirely. It is handled as `DETERMINISTIC` by the `CompetitorComparisonService`, yielding absolute mathematical guarantees.
