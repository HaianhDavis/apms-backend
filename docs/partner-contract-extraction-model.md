# Partner Contract Extraction Model

## Dual Storage Architecture

Partner Contract extraction utilizes a dual-storage model to balance rich, evolving schema requirements (Mongo) with transactional durability and integrity (SQL Server).

### Mongo: `PartnerContractExtractionDraft`
Stores the complete state of an AI extraction generation and review lifecycle.
*   **Idempotency & Auditing:** Immutable fields track exact generation conditions (`contractVersionAtGeneration`, `sourceDocumentHash`).
*   **Document Bounding:** Supports extraction from multiple segments of large documents.
*   **Structured Output:** Stores `metadataFields` and `clauseCandidates` explicitly.
*   **State Machine:**
    *   Generation: `PENDING` -> `COMPLETED` / `PARTIAL` / `FAILED`
    *   Review: `PENDING` -> `IN_REVIEW` -> `REVIEWED`
    *   Application: `NOT_APPLIED` -> `APPLY_PENDING` -> `APPLIED_FROZEN`
    *   Sync: `NOT_REQUIRED` -> `PENDING` -> `SYNCED`

### SQL Server: `PartnerContractApprovalSyncRecord`
Functions as the Outbox pattern table to ensure reliable delivery of final, approved clause data from Mongo into the SQL relational schema.
*   **Durability:** Tracks extraction ID, contract ID, version ID, and clause hash.
*   **State:** `PENDING` -> `PROCESSING` -> `COMPLETED` (or `RETRY` -> `FAILED`)
*   **Distributed Processing:** Uses `locked_by` and `locked_at` for safe multi-instance consumption.

### Application Protocol
An extraction is only "applied" when all required reviews are terminal (`ACCEPT`, `EDIT`).
When applied, Mongo state enters `APPLY_PENDING`. SQL state is updated with `pending_extraction_id` and a `clause_set_hash`. Finally, Mongo commits to `APPLIED_FROZEN`. If the contract is later Approved, an outbox record is generated to sync final clauses to the core SQL schema (`PartnerContractClauseVersion`).

---

## Configuration Properties

All properties live under the `apms.contract-extraction` namespace. Defaults are defined in `ContractExtractionProperties.java` and may be overridden via `application.yml`, `application.properties`, environment variables, or Spring Cloud Config.

| Property | Default | Description |
|---|---|---|
| `segment-chars` | `5000` | Maximum characters per segment sent to the AI provider |
| `segment-overlap-chars` | `200` | Overlap between consecutive segments to preserve cross-boundary context |
| `max-segments` | `20` | Maximum number of segments to process per extraction |
| `max-total-chars` | `100000` | Maximum total characters to process before marking PARTIAL |
| `max-clauses` | `200` | Maximum clause candidates per extraction |
| `provider-timeout-seconds` | `30` | Timeout per AI provider call |
| `max-retries` | `3` | Maximum retries per AI provider call on transient failure |
| `apply-recovery-timeout-seconds` | `300` | Time window (seconds) to recover a stuck APPLY_PENDING draft |

### Override example (application.yml)

```yaml
apms:
  contract-extraction:
    segment-chars: 8000
    segment-overlap-chars: 500
    max-segments: 30
    max-total-chars: 200000
```

### Scheduler

| Property | Default | Description |
|---|---|---|
| `apms.contract-approval-sync.enabled` | (no default, must be `true`) | Enables the approval sync scheduler |

### AI Provider

| Property | Default | Description |
|---|---|---|
| `app.ai.gemini.api-key` | `dummy-key` | Gemini API key |
| `app.ai.gemini.model` | `gemini-2.5-flash` | Gemini model name |
