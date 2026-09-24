# Partner Contract Extraction Workflow

## 1. Generation
*   **Trigger:** Manual trigger by Staff/Manager on a DRAFT or CHANGES_REQUESTED Partner Contract.
*   **Action:** System extracts text from the associated `RawDocument`. If the text exceeds `apms.contract-extraction.segment-chars` (default 5000), it is segmented with `segment-overlap-chars` (default 200) overlap between consecutive segments. Processing is bounded by `max-segments` (default 20), `max-total-chars` (default 100000), and `max-clauses` (default 200). If any limit is reached, the draft is marked `PARTIAL` with `WARNING` quality and an exact count of skipped segments.
*   **Output:** A new `PartnerContractExtractionDraft` is created in Mongo, tracking the exact version of the contract and the document hash.

## 2. Review
*   **Trigger:** Staff reviews extracted fields and clauses.
*   **Action:** Reviewer applies terminal decisions (`ACCEPT`, `EDIT`, `REJECT`, `NEEDS_MORE_DATA`) to each field and clause candidate.
*   **Output:** The draft enters `IN_REVIEW` and eventually `REVIEWED` status once all items have decisions.

## 3. Application
*   **Trigger:** Manual action to apply the reviewed extraction.
*   **Validation:** System verifies the contract hasn't been modified since the draft was generated. All items must have terminal decisions.
*   **Action:**
    1.  Draft status changes to `APPLY_PENDING`.
    2.  Accepted/Edited fields overwrite contract metadata in SQL.
    3.  A deterministic hash of the accepted/edited clauses is saved to the SQL contract as `pending_clause_set_hash`, along with the `pending_extraction_id`.
    4.  Draft status changes to `APPLIED_FROZEN`.

## 4. Contract Approval & Outbox Sync
*   **Trigger:** Manager approves the entire Partner Contract.
*   **Action:**
    1.  The contract state moves to `APPROVED`.
    2.  A new `PartnerContractVersion` is created.
    3.  A new `PartnerContractApprovalSyncRecord` (Outbox) is inserted in the same transaction.
*   **Background Sync:** The Outbox processor picks up the pending sync record, updates the Mongo draft `approvalSyncStatus` to `SYNCED`, and marks the outbox record as `COMPLETED`.

## Regeneration
An extraction can be regenerated. If it was already applied, regeneration requires a `force` flag and a reason, which automatically marks the previous draft as `SUPERSEDED`.
