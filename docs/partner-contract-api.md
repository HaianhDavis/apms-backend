# Partner Contract API

## Authorization
Role requirements enforce strict business workflow rules:
- `STAFF`: Create draft, update draft, submit draft.
- `MANAGER`: Review (Approve/Reject), update lifecycle status, initiate revision.
- `BUSINESS_OWNER`: Read-only access to contracts and versions.
- `ADMIN`: Global bypass. Note: System Admin does NOT automatically perform business manager approvals, business workflows strictly require `MANAGER` role.

## Endpoints

### 1. Create Draft
`POST /api/v1/projects/{projectId}/partner-contracts`
- **Requires**: `STAFF` or `ADMIN`
- **Behavior**: Initializes a `PartnerContract`. Resolves `referenceCompanyId` and `partnerCompanyId` automatically. Scope must be `PARTNER_WITH`.

### 2. Get Contract
`GET /api/v1/partner-contracts/{contractId}`
- **Requires**: `STAFF`, `MANAGER`, `BUSINESS_OWNER`, or `ADMIN`
- **Behavior**: Returns the current state of the contract.

### 3. Update Contract
`PATCH /api/v1/partner-contracts/{contractId}`
- **Requires**: `STAFF` or `ADMIN`
- **Behavior**: Updates factual fields. Contract must be `DRAFT` or `CHANGES_REQUESTED`. Does NOT alter `reviewStatus` or `lifecycleStatus`.

### 4. Submit Contract
`POST /api/v1/partner-contracts/{contractId}/submit`
- **Requires**: `STAFF` or `ADMIN`
- **Behavior**: Validates submit-readiness (e.g. required dates). Transitions `reviewStatus` to `IN_REVIEW`.

### 5. Review Contract
`POST /api/v1/partner-contracts/{contractId}/review`
- **Requires**: `MANAGER` or `ADMIN`
- **Payload**: `{ "decision": "APPROVE|REQUEST_CHANGES", "comment": "..." }`
- **Behavior**:
  - `APPROVE`: Transitions to `APPROVED`, creates immutable `PartnerContractVersion`, increments `currentVersion`. Transactional and idempotent.
  - `REQUEST_CHANGES`: Transitions to `CHANGES_REQUESTED`.

### 6. Update Lifecycle
`POST /api/v1/partner-contracts/{contractId}/lifecycle`
- **Requires**: `MANAGER` or `ADMIN`
- **Payload**: `{ "lifecycleStatus": "PENDING_EFFECTIVE|ACTIVE|EXPIRED|TERMINATED" }`
- **Behavior**: Explicitly updates the real-world lifecycle status, independent of review workflow.

### 7. Start Revision
`POST /api/v1/partner-contracts/{contractId}/revision`
- **Requires**: `MANAGER` or `ADMIN`
- **Behavior**: Transitions an `APPROVED` contract back to `DRAFT` for updates, facilitating a new version cycle without destroying previous approved history.

### 8. Get Versions
`GET /api/v1/partner-contracts/{contractId}/versions`
`GET /api/v1/partner-contracts/{contractId}/versions/{version}`
- **Requires**: `STAFF`, `MANAGER`, `BUSINESS_OWNER`, or `ADMIN`
- **Behavior**: Retrieves immutable historical snapshots of the contract.
