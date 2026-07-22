# Partner Evaluation Source Pinning

## Philosophy
To maintain rigorous auditability and historical accuracy, evaluations must explicitly reference the exact state of a source artifact at the time the evaluation was approved. They cannot depend on mutable objects that can change post-approval.

## ApprovedSourceReference
`ApprovedSourceReference` objects act as the strict linkage mechanism between the evaluation and its underlying data sources.

### Validation Rules
1. **Mutually Exclusive Identifiers**: `sqlSourceId` and `mongoSourceId` are mutually exclusive. Exactly one must be populated based on the `sourceType`.
2. **Missing Metadata Rejection**: Source-specific metadata fields are strictly validated based on `sourceType`. 
    - E.g., `RAW_DOCUMENT_SEGMENT` requires `documentId` and `segmentId`.
    - E.g., `ROLE_METRIC_VERSION` rejects any `documentId` or `externalSourceUrl`.

## CompanyProfileVersion Pinning
Mutable `CompanyProfile` documents are strictly forbidden from being used as official pinned sources. 
When an evaluation requires company profile data:
- It must reference a `CompanyProfileVersion` using its `mongoSourceId`.
- The `ApprovedSourceReferenceFactory` loads the immutable profile version.
- The factory generates a `sourceHash` over the immutable `snapshot` content (MD5).
- It validates the target company alignment (rejects mismatched company records).

## Excluded Functionality
*Note*: Phase 2C.5A implements this pinning validation foundation. It does not implement outbox task synchronization or automated generation capabilities.
