# Owner Company Profile API

This document details the read-only APIs for accessing the active APMS Owner Organization's factual profile and version history.

## 1. Core Principles

- **FPT is the Owner Organization**: The owner identity is configuration-based (`apms.owner.company-profile-id`) and not based on a `CompanyRole`.
- **Factual Data Only**: The owner profile exposes canonical factual data (Identity, Business, Financial, etc.) and does not return any relative scoring data (e.g., AHP weights, role scores).
- **Readiness is Completeness**: Readiness refers solely to whether sufficient factual data exists to perform future comparisons. It is not an evaluation score.
- **Owner Update Workflow is Deferred**: Direct owner-specific update-proposal proxy endpoints are not included in this phase. The standard update workflow requires a project and a task (treating companies as external targets). FPT updates are deferred to a future maintenance module that will reuse the existing approval and versioning system safely without forcing FPT into the external evaluation flow.

## 2. Permissions & Security

All owner APIs utilize standard Spring Security `@PreAuthorize` role checks.
- `SYSTEM_ADMIN`: Full view access.
- `BUSINESS_DEVELOPMENT_MANAGER`: Full view access.
- `BUSINESS_OWNER`: View approved profile, readiness, versions, and reference context.
- `BUSINESS_DEVELOPMENT_STAFF`: View approved profile, readiness, versions, and reference context.

No internal draft data, raw LLM outputs, or unapproved candidate values are exposed via these endpoints.

## 3. Endpoints

### 3.1. `GET /api/v1/owner/company-profile`
Retrieves the active Owner Organization's profile dynamically.

**Behavior:**
- Resolves the configured owner ID.
- Requires the profile to exist and have `reviewStatus == APPROVED`.
- Returns the standard `ProfileResponse` DTO containing full factual sections.

### 3.2. `GET /api/v1/owner/company-profile/readiness`
Verifies if the owner profile is ready for future factual comparison.

**Behavior:**
- Resolves the owner ID.
- Returns an `OwnerProfileReadinessResponse` detailing the `completedSections` and `missingSections`.
- Requires the `CompanyProfile` to exist, be `APPROVED`, contain minimal `identity` and `business` fields, and have a corresponding `CompanyProfileVersion` snapshot.

### 3.3. `GET /api/v1/owner/company-profile/versions`
Retrieves the version history for the active owner profile.

**Behavior:**
- Delegates to the standard `CompanyProfileVersionService`.
- Returns a paginated list of versions, sorted newest-first.

### 3.4. `GET /api/v1/owner/company-profile/versions/{version}`
Retrieves a specific historical snapshot of the owner profile.

**Behavior:**
- Retrieves the snapshot at the specified version explicitly for the owner.

### 3.5. `GET /api/v1/owner/reference-context`
Builds and returns a specialized `ReferenceCompanyContextResponse` for the owner.
(See `docs/reference-company-context.md` for full details).

## 4. Error Handling

- **Owner profile missing**: "CompanyProfile not found for ID: ..."
- **Owner profile not approved**: "CompanyProfile must be approved."
- **Version snapshot missing**: "Owner CompanyProfile version snapshot was not found."

Optional factual sections (like Financials or Innovation) being missing does not block the reference context or profile retrieval; it only affects the readiness and availability flags.

## 5. Target-Company Filtering
The owner organization (FPT) remains visible in generic profile searches for administrative/audit needs. To exclude the owner from external evaluation lists or target selectors, clients must explicitly pass the `excludeOwner=true` parameter to the generic `/api/v1/company-profiles` and `/api/v1/profiles/search` list endpoints.
