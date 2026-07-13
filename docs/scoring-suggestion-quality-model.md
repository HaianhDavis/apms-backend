# Scoring Suggestion Quality Model

## Overview
This document outlines the quality evaluation model for AI-generated criterion suggestions within the Role Evaluation system (APMS Phase 2C).

Unlike the factual AI extraction quality framework, which applies to `CompanyProfile` and verifies absolute truth against raw text, the **scoring suggestion quality model** evaluates subjective assessments, AI reasoning, and logical derivations against predefined rubrics.

## Core Quality Entity
Each `AutomaticSuggestion` embedded in a `RoleEvaluationDraft` tracks its own quality metadata.

### Quality Fields
- `evidenceCoverage`: The percentage of required rubric sub-components satisfied by the evidence.
- `missingData`: A list of business metrics or facts required by the rubric that the AI explicitly identified as missing.
- `validationWarnings`: A list of warnings produced by the validation engine (e.g. low coverage, suspicious score jumps).
- `validationStatus`: The strict programmatic validation outcome (PASS, WARNING, FAIL).
- `confidence`: The AI's self-reported confidence score (0.0 - 1.0).
- `reviewStatus`: The human review decision (ACCEPTED, EDITED, REJECTED, NEEDS_MORE_DATA).

## Validation Engine
The `CriterionSuggestionValidator` processes suggestions and returns a `SuggestionValidationResult`.

### Validation Outcomes
1. **PASS**: Score is between 0-100, confidence > 0.4, coverage > 0.5.
2. **WARNING (Non-blocking)**: Suggestion has minor quality issues (e.g. coverage < 0.5 but score is generated, or confidence < 0.4). Can be accepted, but requires a human explanation.
3. **WARNING (Blocking)**: Score is radically misaligned with coverage, or the AI hallucinated a score despite missing critical data. Cannot be accepted as-is; must be edited or rejected.
4. **FAIL**: The suggestion violates invariants (e.g. score out of bounds, missing required keys). Cannot be accepted.

## Human Review Workflow
The API exposes four explicit actions for human reviewers:
1. **Accept**: Converts the suggestion into a `CriterionInput` via `AUTOMATIC_PROPOSAL`. Fails if validation is FAIL, blocking WARNING, or NEEDS_MORE_DATA.
2. **Edit**: Modifies the score via `MANUAL_OVERRIDE` with an explicit reason.
3. **Reject**: Completely removes the suggestion's input impact. Removes any existing `AUTOMATIC_PROPOSAL`.
4. **Needs More Data**: Formalizes the lack of data. Nullifies the raw score and tracks `missingData` while awaiting more evidence. Cannot be accepted.

## Backward Compatibility
Legacy fields (`componentCoverage`, `missingComponents`, `calculationWarnings`, `accepted`) are retained for backwards compatibility with earlier frontend implementations.
- `effectiveExplanation` acts as a fallback for `suggestionRationale`.
- `effectiveReviewStatus` interprets `accepted=true` as `ACCEPTED`.
- Persistence via `MappingMongoConverter` ensures that nulls in the new canonical fields do not break deserialization of old active drafts.
