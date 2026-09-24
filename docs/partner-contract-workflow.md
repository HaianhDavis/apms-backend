# Partner Contract Workflow

## Phase 2C.3A: Approval and Revision Lifecycle

The partner contract operates on a strict state-machine decoupling the real-world **lifecycle status** from the **review status** of data entry.

### 1. Draft Creation (`DRAFT`)
A `STAFF` member (e.g., Business Development) creates a contract draft via `POST /api/v1/projects/{projectId}/partner-contracts`.
The project must have a scope of `PARTNER_WITH`. The `referenceCompanyId` is strictly resolved from the owner organization; `partnerCompanyId` is strictly resolved from the Project.

### 2. Fact Updates
While in `DRAFT` or `CHANGES_REQUESTED`, Staff can edit facts (e.g., dates, amounts, currency) and link the `rawDocumentId`.
Real-world lifecycle status (e.g., `ACTIVE`, `EXPIRED`) cannot be changed through this generic update.

### 3. Submission (`IN_REVIEW`)
Staff invokes `POST /api/v1/partner-contracts/{contractId}/submit`. This validates that all required fields (e.g., contractNumber, effectiveDate) are present. The state changes to `IN_REVIEW`. The contract is now locked from Staff edits.

### 4. Manager Review (`APPROVED` or `CHANGES_REQUESTED`)
A `MANAGER` reviews the contract via `POST /api/v1/partner-contracts/{contractId}/review`.
- **Approve**: The contract state becomes `APPROVED`. An immutable `PartnerContractVersion` snapshot is immediately created and `currentVersion` is incremented. The operation is transactional, idempotent, and protected by optimistic locking.
- **Request Changes**: The state becomes `CHANGES_REQUESTED`. Staff can edit and re-submit.

### 5. Lifecycle Transition
A `MANAGER` manages real-world status (e.g., activating or terminating the contract) via a dedicated endpoint: `POST /api/v1/partner-contracts/{contractId}/lifecycle`.

### 6. Revision Workflow
If factual data must be corrected after an approval, a `MANAGER` invokes `POST /api/v1/partner-contracts/{contractId}/revision`. This resets the `reviewStatus` back to `DRAFT` so Staff can update facts. Upon the next approval, a new immutable version is created without overwriting the prior history.
