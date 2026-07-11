# FPT Owner Company Profile Audit

## 1. Current Architecture & Owner Organization Implementation

Currently, the APMS "Owner Organization" is implemented as a hardcoded dummy entity rather than a full system record.

* **Representation:**
  * **Constant ID:** The Owner Organization is defined as a static constant `6a31a0000000000000000000` (`DEMO_ORG_ID` / `OWNER_ORG_COMPANY_ID`).
  * **Neo4j Node:** The seeder creates a Neo4j node for this ID with the name "APMS Demo Organization".
  * **CompanyProfile (Mongo):** **Missing.** There is no actual factual `CompanyProfile` document saved in the database for the Owner Organization.
* **Hardcoded Dependencies:** The ID is currently hardcoded in multiple critical services:
  * `GraphService` (used as the root node for relationship queries)
  * `ProjectService` (used to prevent projects from targeting the owner org)
  * `OwnerAssistantContextService` (used to load AI context)
  * `AssistantDemoDataSeeder` (used to build the initial graph)

## 2. Current FPT Demo Profile

FPT Corporation is currently seeded as a standard, external target company:
* **CompanyProfile:** Exists in MongoDB with ID `6a31a0000000000000000001`.
* **Neo4j Node:** Exists as a separate node in the graph.
* **Relationships:** In the current demo graph, the dummy APMS Owner Organization has a `PARTNER_WITH` relationship to FPT.

## 3. Duplicate-Risk Analysis

Converting FPT into the Owner Organization carries several duplication and consistency risks if not migrated carefully:
* **Node Duplication:** If we simply change the constant `OWNER_ORG_COMPANY_ID` to `6a31a0000000000000000001` (FPT), the old `6a31a0000000000000000000` (Demo Org) node will become an orphaned or duplicate root node in Neo4j.
* **Self-Referencing Edges:** The existing `PARTNER_WITH` relationship between Demo Org and FPT would result in FPT being partnered with itself.
* **Score Snapshots:** If there are existing `score_snapshots` referencing FPT as a target company, they will become logically invalid because the owner organization cannot be a target of an APMS evaluation project.

## 4. Field Coverage Matrix

The `CompanyProfile` entity currently supports many required fields, but is missing several specific to an in-depth reference company.

| Category | Supported? | Location in `CompanyProfile` |
| :--- | :--- | :--- |
| **Identity** | Yes | `identity` (legalName, tradeName, taxCode) |
| **Industries** | Yes | `business.industries` |
| **Business Model** | Yes | `business.businessModel` |
| **Products/Services** | Yes | `business.products` |
| **Target Markets** | Yes | `business.targetCustomers` / `business.markets` |
| **Geographic Markets** | Yes | `business.markets` |
| **Customer Segments** | Yes | `business.targetCustomers` |
| **Technologies** | **No** | Missing. Needs a new field. |
| **Capabilities** | **No** | Missing. Needs a new field. |
| **Company Size** | Yes | `companySize` |
| **Headquarters** | Yes | `contact.addresses` |
| **Financial Info** | **No** | Missing. Needs a new field. |
| **Strategic Direction** | **No** | Missing. Needs a new field. |
| **Certifications** | **No** | Missing. Needs a new field. |
| **Compliance/Risk** | **No** | Missing. Needs a new field. |
| **Source Documents** | Yes | `sourceRefs.rawDocumentIds` |
| **Profile Version** | Yes | `version` |
| **Review Status** | Yes | `reviewStatus` |

## 5. Scoring Separation

**Confirmed:** The `CompanyProfile` entity correctly adheres to the principle of containing *factual business data only*.
* External-role evaluation fields (e.g., `partner_fit_score`, `competition_level`, `relationship_strength`) are completely isolated in the `score_snapshots` table (SQL Server) and the `CompanyCandidate` draft entity (`ScorePreview`).
* **Note on Insights:** `CompanyProfile` contains an `insights` block (SWOT: `strengths`, `weaknesses`, `opportunities`, `threats`). While useful for targets, SWOT is inherently relative. An Owner Profile should generally be purely factual, but keeping the schema unified is fine as long as scoring remains separated.

## 6. Recommended Migration Strategy

To safely elevate FPT to be the APMS Owner Organization, we should:
1. **Refactor the Constants:** Move the hardcoded `OWNER_ORG_COMPANY_ID` to an application property (e.g., `application.yml` -> `apms.owner.company-id`).
2. **Schema Update:** Add the missing fields (`technologies`, `capabilities`, `financials`, `strategicDirection`, `certifications`, `compliance`) to the `CompanyProfile` and `CompanyCandidate` structures.
3. **Data Migration / Seeder Update:**
   * Delete or omit the dummy `6a31a0000000000000000000` from the seeder.
   * Assign FPT the role of Owner Organization by setting the application property to FPT's ID (`6a31a0000000000000000001`).
   * Remap all Neo4j edges: Instead of `<Target> -> <Demo Org>`, map them as `<Target> -> <FPT>`.
   * Remove any evaluation/score snapshots that evaluate FPT as a target.

## 7. Files Expected to be Modified
* `CompanyProfile.java` (add missing fields)
* `CompanyCandidate.java` (add missing fields for parity)
* `application.yml` (add owner property)
* `AssistantDemoDataSeeder.java` (re-wire nodes and edges to make FPT the center)
* `GraphService.java` (inject property instead of using constant)
* `ProjectService.java` (inject property instead of using constant)
* `OwnerAssistantContextService.java` (inject property instead of using constant)
