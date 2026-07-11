# Owner Company Profile API Audit

## 1. Existing Profile Endpoints
The `ProfileController` exposes:
- `GET /api/v1/company-profiles` (Search with filters)
- `GET /api/v1/profiles/{companyId}` (Get single profile)
- `GET /api/v1/profiles/search?name=` (Autocomplete/Search by name)
- `GET /api/v1/profiles/{companyId}/sources` (Source document IDs)
- `PATCH /api/v1/company-profiles/{companyId}` (Update by admins)
- `DELETE /api/v1/company-profiles/{companyId}` (Delete by admins)

## 2. Existing Profile Version Endpoints
The `CompanyProfileVersionController` exposes:
- `GET /api/v1/company-profiles/{companyProfileId}/versions`
- `GET /api/v1/company-profiles/{companyProfileId}/versions/{version}`

## 3. Existing Update Proposal Endpoints
The `CompanyProfileUpdateProposalController` exposes:
- `POST /api/v1/projects/{projectId}/tasks/{taskId}/profile-update-proposals`
- `POST /api/v1/projects/{projectId}/tasks/{taskId}/profile-update-proposals/from-extractions`
- `GET /api/v1/profile-update-proposals/{id}`

## 4. Existing Source/Provenance Endpoints
`ProfileController` provides `GET /api/v1/profiles/{companyId}/sources` which maps to `ProfileSourcesResponse` containing sets of `projectIds`, `importJobIds`, `rawDocumentIds`, and `candidateIds`.

## 5. Existing Authorization Behavior
Endpoints check roles via Spring Security `@PreAuthorize("hasAnyRole(...)")`.
- `SYSTEM_ADMIN` and `BUSINESS_DEVELOPMENT_MANAGER` can update profiles.
- `BUSINESS_OWNER`, `BUSINESS_DEVELOPMENT_MANAGER`, and `BUSINESS_DEVELOPMENT_STAFF` can read profiles, versions, and sources.
- This maps perfectly to the requested permission requirements.

## 6. Which Endpoints Can Be Reused Unchanged
- `GET /api/v1/company-profiles/{companyProfileId}/versions` and `{version}` can be reused for owner profile version history if the client supplies the FPT companyProfileId. We will also add owner-specific convenience endpoints that delegate to this service.
- Update proposal endpoints can be reused by clients without change as long as they pass the active FPT `companyProfileId`.
- The `ProfileResponse` DTO already includes all required factual fields (`identity`, `business`, `financial`, `market`, `innovation`, `risk`, `compliance`, `insights`, `sourceRefs`, `reviewStatus`, `version`) and does not include any role evaluation scores. This can be directly reused.

## 7. Which Owner-Specific Convenience Endpoints Are Still Needed
- `GET /api/v1/owner/company-profile`: Retrieve the active Owner Organization profile dynamically without hardcoding its ID.
- `GET /api/v1/owner/company-profile/readiness`: Verify if the owner profile is ready for comparison.
- `GET /api/v1/owner/company-profile/versions` and `GET /api/v1/owner/company-profile/versions/{version}`: Automatically resolves the owner profile ID and returns its versions.
- `GET /api/v1/owner/reference-context`: Build and return the specialized ReferenceCompanyContextResponse.

## 8. Can Current Profile APIs Expose Unapproved Profiles?
Currently, `ProfileService.getProfileByCompanyId` only checks for `isDeleted`. We must ensure the new owner endpoints explicitly require `reviewStatus == "APPROVED"`.

## 9. Is FPT Excluded from Active Target-Company Lists?
Currently, `ProfileService.searchCompanyProfiles` and `searchProfilesByName` do not exclude the owner organization ID. They only filter by `isDeleted != true`. We need to update these list methods to explicitly exclude `ownerOrganizationService.getOwnerCompanyId()`. We will also review dashboard and reporting queries to ensure FPT is similarly excluded from aggregate target lists.

## 10. Minimal Implementation Approach
- Use `ProfileResponse` directly for the owner profile read endpoint.
- Create `OwnerCompanyProfileController` mapped to `/api/v1/owner`.
- Expose `toResponse(CompanyProfile)` in `ProfileService` as a public method (e.g. `mapToProfileResponse`).
- Inject `OwnerOrganizationService` into `ProfileService` and `OwnerCompanyProfileController`.
- Delegate the versions endpoints in `OwnerCompanyProfileController` to `CompanyProfileVersionService`.
- Create `ReferenceCompanyContextService` and `ReferenceCompanyContextResponse` DTO for the new `GET /api/v1/owner/reference-context` endpoint.
- Implement `ComparisonInputAvailability` logic within `ReferenceCompanyContextService`.

## 11. Final Decisions
- **ProfileResponse Reuse**: We will reuse `ProfileResponse` rather than creating a duplicate full factual profile DTO.
- **Update Workflow Deferral**: No owner-specific update-proposal wrapper endpoint will be added in this phase. The current workflow treats companies as external targets (requiring a project/task). The owner update workflow is deferred to a future maintenance module that reuses the approval and versioning structures without forcing FPT into the external evaluation flow.
- **Generic Search Visibility**: FPT will NOT be globally excluded from generic profile searches (e.g. `searchCompanyProfiles`, `searchProfilesByName`), as these must serve internal admin/audit needs. FPT will only be explicitly excluded from active target selectors and evaluation lists.
- **Dashboard Semantics**: Dashboard counts represent the total entities present in the APMS database, so FPT remains included in the overall profile count.
