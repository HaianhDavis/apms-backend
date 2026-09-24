# FPT Owner Migration Guide

## 1. Goal
Promote the existing FPT Corporation `CompanyProfile` to be the sole Owner Organization and Reference Company in APMS. 

## 2. IDs
- **Old Demo Owner ID**: `6a31a0000000000000000000`
- **New FPT Owner ID**: `6a31a0000000000000000001`

## 3. Architecture Changes
- Changed the default owner configuration from the old Demo Owner ID to the FPT Owner ID.
- Promoted FPT to Owner. 
- Generated a `CompanyProfileVersion` snapshot for FPT if it doesn't already exist upon seeding.
- Migrated Neo4j relationships. The canonical relationship format is `OwnerCompany -[RELATIONSHIP_TYPE]-> TargetCompany` where the target company's role is described by the relation. FPT no longer targets itself (`PARTNER_WITH`).
- Incoming relationships like `SUPPLIER_OF` and `CUSTOMER_OF` were also normalized to originate from the Owner (e.g. `FPT -[SUPPLIER_OF]-> AWS`).
- Excluded FPT from targeting projects in `ProjectService`.
- Excluded FPT from candidate approval evaluation to avoid FPT turning into an evaluated company.
- Preserved historical score snapshots but stopped seeding new FPT-as-target snapshots.
- Set up dynamic display text in `OwnerAssistantContextService`.
- No new `OWNER` role was added to `CompanyRole`.
- No role scoring was implemented.

## 4. Configuration Changes
`application.properties`, `application-dev.properties` and `OwnerOrganizationProperties` default `companyProfileId` changed to `6a31a0000000000000000001`.

## 5. Dev vs Production Migration Behavior
In `dev` and `demo` environments, the graph migration and cleanup (like deleting the old APMS Demo Organization node) happens automatically at startup via `AssistantDemoDataSeeder`. 
For `production`, no automated destruction takes place. A manual, scheduled migration script should be executed intentionally.

## 6. Rollback Steps
To rollback the owner ID:
1. Revert the `apms.owner.company-profile-id` to `6a31a0000000000000000000`.
2. Re-create the `6a31a0000000000000000000` node in Neo4j.
3. Rewire relationships back to `6a31a0000000000000000000`.
