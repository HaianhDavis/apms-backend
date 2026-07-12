# Company Profile Identifier Convention
**Phase 2B — Design Decision**
**Document Type:** Convention Specification
**Version:** 1.0

---

## 1. Context and Problem Statement

The APMS `CompanyProfile` entity currently contains two primary identity fields:
- `id` (String): The MongoDB native `_id` ObjectId.
- `companyId` (String): A UUID generated at creation, used as a foreign key in Neo4j (`CompanyNode`) and SQL Server legacy `ScoreSnapshot`s.

In the `AssistantDemoDataSeeder`, these two fields are artificially seeded with the exact same 24-character hexadecimal string (e.g., `"6a31a0000000000000000001"`). However, in a production environment, `id` is a MongoDB ObjectId and `companyId` is a 36-character UUID.

The canonical role-scoring infrastructure requires clear, unambiguous field names and lookup conventions to prevent semantic mismatch between stable business identifiers and database primary keys.

---

## 2. Identifier Audit

| Entity / Service | Field / Parameter | Actual Semantic | Notes |
|---|---|---|---|
| `CompanyProfile` | `id` | MongoDB ObjectId | MongoDB Primary Key |
| `CompanyProfile` | `companyId` | UUID | Stable business ID for cross-db (Neo4j, SQL) |
| `CompanyProfileVersion` | `companyProfileId` | MongoDB ObjectId | Indexed, maps to `CompanyProfile.id` |
| `CompanyProfileVersion` | `companyId` | UUID | Indexed, maps to `CompanyProfile.companyId` |
| `Project` | `targetCompanyProfileId` | UUID | stable business identifier |
| `OwnerOrganizationService` | `companyProfileId` | MongoDB ObjectId | Looks up using `CompanyProfileRepository.findById()` |
| `CanonicalScoreSnapshotService` | `targetCompanyProfileId` (Phase 1 param) | MongoDB ObjectId | Must be mapped explicitly |

---

## 3. Decision: Explicit Canonical Identifiers

To prevent ambiguous field names (like `targetCompanyProfileId`) from conflating MongoDB IDs and stable business IDs, the `RoleEvaluationDraft` MUST use four explicit fields:

### Field Definitions

1. **`targetCompanyId`**
   - Semantic: `CompanyProfile.companyId` (UUID)
   - Purpose: Stable business identifier. Matches `Project.targetCompanyProfileId`.
   - Example: `"550e8400-e29b-41d4-a716-446655440000"`

2. **`targetProfileDocumentId`**
   - Semantic: `CompanyProfile.id` (MongoDB `_id`)
   - Purpose: Primary key for direct MongoDB document lookup.
   - Example: `"64fa58d20c5d3d1f8a9e4b11"`

3. **`referenceCompanyId`**
   - Semantic: Configured FPT stable company ID (UUID).

4. **`referenceProfileDocumentId`**
   - Semantic: FPT `CompanyProfile` MongoDB `_id`.

### Operational Rules

1. **No Ambiguous Names:** `RoleEvaluationDraft` must not use `targetCompanyProfileId` or `referenceCompanyProfileId`.
2. **Repository Lookups:** Profile-version repository lookups MUST use the identifier expected by the actual repository model (`findByCompanyProfileIdAndVersion` expects the MongoDB `_id`).
3. **Seed Data Independence:** Do not rely on seed data having identical `id` and `companyId`. Always query the profile to resolve the correct `id` and `companyId`.
