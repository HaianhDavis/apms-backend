# Partner Evaluation AI Boundaries

## Architectural Isolation
In the APMS system, the AI boundaries for Partner Role Evaluations enforce strict isolation between generation of suggestions and the core scoring engine.

### Generation vs. Persistence
- **AI Suggestion Generation**: The `PartnerSuggestionGenerationService` communicates with the LLM via `AiCriterionSuggestionProvider` implementations (e.g., `GeminiCriterionSuggestionProvider`). The AI is permitted to analyze pinned source data (e.g. contracts, role metrics) and generate a `PartnerCriterionSuggestionResponse` which includes `rationale`, `confidence`, and `missingDataNotes`.
- **Validation Strictness**: The `PartnerAiSuggestionValidator` intercepts the raw AI JSON response. It validates the output recursively. Any attempt by the AI to populate scoring fields (e.g., `score`, `rawScore`, `ahpWeight`, `overallScore`, `companyRole`) is strictly rejected. A canonical allowlist (`ALLOWED_FIELDS`) enforces that only qualitative analysis elements are processed.

### Absence of Side Effects
The suggestion generation flow guarantees zero side-effects on the scoring and persistence systems:
- It does **not** call the `RoleScoringEngine` (no recalculation of scores).
- It does **not** create a `ScoreSnapshot`.
- It does **not** alter the `CompanyProfile` or `Neo4j` relationships.
- It does **not** alter `RoleEvaluationVersion` state or dispatch outbox events.

AI outputs remain fully ephemeral until explicitly reviewed and accepted by a human.
