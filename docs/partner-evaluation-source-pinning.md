# Partner Evaluation Source Pinning

## Immutability Guarantee
The fundamental principle of Partner Evaluation drafts is that they operate against a fixed point in time. This is achieved through strict source pinning. 

### Pinned Source References
- The `RoleEvaluationDraft` explicitly stores `pinnedSourceReferences` containing `ApprovedSourceReference` records.
- These references form an immutable snapshot boundary for the evaluation logic.

### Context Provider Validation
The `PartnerEvaluationContextProvider` guarantees the integrity of this boundary during context generation:
1. **Source Loading via Pinning**: It reads strictly from `RoleEvaluationDraft.pinnedSourceReferences` and dynamically fetches the immutable approved source versions (e.g. from `CompanyProfileVersionRepository`).
2. **Re-Hashing Strategy**: During source loading, the context provider delegates to `SourcePinningValidator` to calculate deterministic hashes and references. This ensures the underlying data hasn't drifted.
3. **Period Relevance**: Sources are filtered contextually against evaluation boundaries.

### Idempotency
- Multiple generations for the same criterion and the same `sourceSnapshotHash` are inherently idempotent.
- If the underlying `sourceSnapshotHash` diverges (due to content changes in underlying versions or modification in the draft's pinned list), generation detects a conflict or returns a stale status.
