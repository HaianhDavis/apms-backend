# Partner Contract Data Model

## SQL Ownership
`PartnerContract` and `PartnerContractVersion` are natively mapped to SQL Server to leverage ACID transactions, strict schema constraints, and optimistic locking (`@Version`). They are the official factual source of contract metadata.

## Entity Structure

### PartnerContract
Stores the *current* state of the contract (either DRAFT, IN_REVIEW, or CHANGES_REQUESTED for in-flight work, or APPROVED for the finalized state).

- **id**: Primary Key, auto-increment
- **referenceCompanyId**: UUID of the APMS owner organization (FPT). Must be derived via `OwnerOrganizationService`.
- **partnerCompanyId**: UUID of the partner organization. Derived from the Project target.
- **sourceProjectId**: Originating project (must be `PARTNER_WITH`).
- **sourceTaskId**: Originating task (optional).
- **rawDocumentId**: Link to the original contract document in MongoDB. Provides document traceability but does not duplicate the file payload.
- **reviewStatus**: Workflow state (`DRAFT`, `IN_REVIEW`, `APPROVED`, `CHANGES_REQUESTED`).
- **lifecycleStatus**: Real-world contract state (`PENDING_EFFECTIVE`, `ACTIVE`, `EXPIRED`, `TERMINATED`).
- **Dates**: `signedDate`, `effectiveDate`, `expiryDate`.
- **Value**: `totalContractValue` and normalized `currency` (ISO 4217).
- **currentVersion**: Counter tracking the number of times this contract was approved.

### PartnerContractVersion
Stores *immutable snapshots* of the `PartnerContract`. Only created when a Manager successfully approves an `IN_REVIEW` contract. It mirrors all factual fields and is bound by a unique composite constraint `(contractId, version)`.

## Future RoleMetricRecord Linkage
The `PartnerContract` is designed to act as the factual anchor for Phase 2C.4. Future `RoleMetricRecord` entities will link to `partnerContractId` and `contractVersion`. KPI actuals, scores, and relationship metrics are intentionally *excluded* from the contract data model. No AI extraction occurs in Phase 2C.3A.

## Null / Unknown Behavior
Factual fields (dates, amounts) that are unknown are stored as strictly `null`. They are not converted to defaults (e.g., zero, current date, or default currency).
