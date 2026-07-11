# FPT Owner Migration Audit

## 1. Current Owner Architecture
- **Owner ID:** `6a31a0000000000000000000` (APMS Demo Organization)
- **Database Presence:** Currently represented only as a Neo4j node. No corresponding MongoDB `CompanyProfile` exists.

## 2. Existing FPT Architecture
- **CompanyProfile ID:** `6a31a0000000000000000001`
- **Database Presence:** Exists as both a MongoDB `CompanyProfile` and a Neo4j node.
- **Review Status:** Seeded as `APPROVED`.
- **Version:** Default version `1` on the `CompanyProfile` document. No separate `CompanyProfileVersion` records are seeded currently.
- **Classification:** The `CompanyProfile` model does not natively store roles like `OWNER`. Instead, roles are graph relationships. FPT currently has a `PARTNER_WITH` incoming relationship from the Old Owner.

## 3. Hardcoded / Reference Usage
References to the old Demo Organization and FPT were found in:
- `application.properties` and `application-dev.properties` (defining `apms.owner.company-profile-id`).
- `OwnerOrganizationProperties` (default fallback).
- `AssistantDemoDataSeeder` (seeds the dummy node and creates `FPT_ID = 6a31a0000000000000000001`).
- `OwnerAssistantContextService` (hardcoded text `Owner Organization: APMS Demo Organization`).

## 4. Existing Graph Relationships
- **Old Owner Outgoing Relationships:**
  - `PARTNER_WITH` -> FPT, MICROSOFT
  - `COMPETITOR_OF` -> CMC, VNG, VNPT
  - `POTENTIAL_PARTNER_OF` -> VIETTEL, MOMO
- **Old Owner Incoming Relationships:**
  - AWS `SUPPLIER_OF` -> Old Owner
  - RETAILPLUS `CUSTOMER_OF` -> Old Owner
- **FPT Outgoing Relationships:**
  - `COMPETITOR_OF` -> CMC, VNG
- **Self-Relationship Risk:** Migrating Old Owner's `PARTNER_WITH -> FPT` to FPT would result in `FPT -[PARTNER_WITH]-> FPT`. This must be prevented.

## 5. Projects
- **FPT as Target:** The seeder creates a project targeting "Vietnam Tech Industry", not FPT explicitly. However, any user-created legacy project targeting FPT must be preserved.
- **Target Validation:** `ProjectService` currently blocks projects targeting the active owner ID. Once FPT becomes owner, new projects targeting FPT will be correctly rejected.

## 6. Score Snapshots
- **FPT as Target:** `AssistantDemoDataSeeder` creates a `ScoreSnapshot` for FPT (`ensureScore(project, FPT_ID, ...)`). FPT thus appears as an evaluated target.
- **Production Counts:** No production data counts are fabricated. The existing snapshots will be preserved as-is.

## 7. Profile Versions
- **Current State:** FPT does not currently have seeded `CompanyProfileVersion` records.
- **Capability:** `CompanyProfileVersion` natively supports the full factual profile via `Map<String, Object> snapshot`.

## 8. Owner AI Assistant
- **Context Resolution:** `OwnerAssistantContextService` resolves relationships dynamically based on the configured Owner ID. However, it contains hardcoded display strings (`APMS Demo Organization`) which must be made dynamic using the actual resolved profile name.

## 9. Audit Logging
- **Current Actions:** The `AuditAction` enum covers many entity lifecycles, but lacks specific events for this migration.
- **New Actions Needed:** We can add `OWNER_ORGANIZATION_MIGRATED`, `OWNER_PROFILE_PROMOTED`, `OWNER_GRAPH_ROOT_MIGRATED` if the enum can be modified safely.

## 10. Recommended Migration Order
1. Ensure FPT profile exists and is approved.
2. Ensure FPT profile version exists.
3. Change configured owner ID to FPT.
4. Rewire Neo4j owner relationships.
5. Remove old owner-to-FPT relationship.
6. Prevent FPT self-relationships.
7. Stop seeding APMS Demo Organization.
8. Preserve historical projects/snapshots.
9. Update reports/assistant context.
10. Verify idempotency.
## 11. Final Decisions
- **Graph Direction Decision**: The canonical graph direction is `OwnerCompany -[RELATIONSHIP_TYPE]-> TargetCompany` where FPT is the source of all relationships representing its ecosystem.
- **Relationship Type Semantic Convention**: The relationship type describes the target's role relative to the owner, even where the enum name reads like a natural-language predicate.
- **Environment-Safe Migration Decision**: Automatic graph destruction (like deleting the old dummy owner node) is executed *only* in `dev`/`demo` environments (via `AssistantDemoDataSeeder`). Production migration must be intentional and manual, ensuring no silent destruction of production data.
