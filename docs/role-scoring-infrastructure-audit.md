# Role Scoring Infrastructure Audit

## 1. Executive Summary
This document provides a comprehensive audit of the current APMS scoring infrastructure against the future canonical role-specific score rules target architecture. The audit focuses on database schema, application entities, workflows, APIs, reporting, and AI dependencies, preparing a roadmap for replacing legacy scoring logic with an automated, role-aware evaluation engine based on AHP weights.

## 2. Existing Score Architecture
The current backend scoring implementation is highly rigid and relies heavily on hardcoded mockup behavior instead of an evaluation engine:
- The `ScoreService` listens to the `CandidateApprovedEvent` and immediately generates a mockup `ScoreSnapshot`.
- Legacy fields (`partnerFitScore`, `competitionLevel`, `riskLevel`, `relationshipStrength`, `totalScore`) are embedded directly into entity columns.
- The `ScoreRule` entity exists but is entirely disconnected from actual scoring; no runtime calculations read from it.
- Scoring is evaluated globally per company, lacking support for varying evaluation perspectives (Roles).
- AI Assistants refer to the SQL score tables but currently serve global values without context of target relationship roles.

## 3. ScoreRule Entity/Table
**Current Semantic Model**: Appears designed as a single global condition-based rule-set rather than criterion-specific configurations.
**Actual Database Structure (Hibernate Derived)**:
- `id` (BIGINT, PK)
- `rule_name` (VARCHAR(255), NOT NULL)
- `rule_category` (VARCHAR(255), NOT NULL) - (e.g., PARTNER_FIT, COMPETITION, RISK, RELATIONSHIP)
- `weight` (INT, NOT NULL)
- `rule_condition_json` (VARCHAR(1000), NULL)
- `is_active` (BIT/BOOLEAN, NULL)
- `created_by` (BIGINT, FK to account, NULL)
- `project_id` (BIGINT, FK to project, NULL)
- `created_at` (DATETIME, NOT NULL, updatable = false)
- `updated_at` (DATETIME, NOT NULL)

**Missing Infrastructure for Target Architecture**:
- `evaluatedRole` (Enum: PARTNER, COMPETITOR, etc.)
- `criterionKey` & `criterionName` (Specific canonical identifiers)
- `direction` (BENEFIT, COST, THREAT)
- `scoringMethod` & `weightingMethod` (e.g., AHP)
- `weightSource` (e.g., ILLUSTRATIVE, EXPERT_APPROVED)
- `ruleSetVersion` (Grouping versions of scoring sets)
- `effectiveFrom` & `effectiveTo` (Temporal validity)

## 4. ScoreSnapshot Entity/Table
**Current Semantic Model**: Represents one global score for a target company candidate based on an implied single evaluation at the time of approval.
**Actual Database Structure (Hibernate Derived)**:
- `score_snapshot_id` (BIGINT, PK)
- `company_id` (VARCHAR(36), NOT NULL)
- `project_id` (BIGINT, FK to project, NOT NULL)
- `candidate_id` (VARCHAR(255), NOT NULL)
- `partner_fit_score` (INT, NULL)
- `competition_level` (INT, NULL)
- `risk_level` (INT, NULL)
- `relationship_strength` (INT, NULL)
- `total_score` (INT, NULL)
- `factors_json` (NVARCHAR(MAX), NULL)
- `rule_version` (VARCHAR(255), NOT NULL)
- `generated_by_account_id` (BIGINT, FK to account, NULL)
- `created_at` (DATETIME, NOT NULL, updatable = false)

**Missing Infrastructure for Target Architecture**:
- `targetCompanyProfileId` & `targetProfileVersion`
- `referenceCompanyProfileId` & `referenceProfileVersion` (For FPT context)
- `evaluatedRole` (Permits multiple snapshots for the same company under different roles)
- `criterionScores` & `normalizedCriterionScores` (JSON/Map of dynamic criteria)
- `weightsUsed` & `weightVersion` (Provenance of weights applied)
- `scoreRuleVersion` (Replacing general `rule_version`)
- `overallScore` (Added side by side; `total_score` remains legacy read-only)
- `completenessStatus` & `missingCriteria` (Handling null inputs properly)
- `calculatedAt` & `evidence` / source references

## 5. ScoreService Behavior
- **Calculation Trigger**: Automatic upon `CandidateApprovedEvent`. There is no manual trigger or recalculation logic.
- **Rule Usage**: Hardcoded mock values (85, 30, 15, 90). `ScoreRule` records in the DB are ignored.
- **Weights**: No weights are currently applied to scores.
- **ScorePreview Usage**: `ScorePreview` is ignored; it does not map to `ScoreSnapshot`.
- **Snapshot Immutability**: Saves a new record per event; does not update existing.
- **Missing Values**: Values are mocked, so missing value handling is non-existent.
- **Client Scoring**: Clients cannot submit scores via API.
- **Calculation Timing**: Post-approval.
- **Multi-role Support**: Unsupported. A single company globally receives one set of static legacy scores per candidate ID.
- **Project Relationship Usage**: Ignores `targetRelationshipType` completely.

## 6. ScoreController APIs
- `GET /api/v1/profiles/{companyId}/scores`: Returns a list of `ScoreSnapshotDto`. Client cannot submit values. Role is inferred globally.
- `GET /api/v1/score-rules`: Returns a list of `ScoreRuleDto`.
- `POST /api/v1/score-rules`: Creates a rule.
- `PUT /api/v1/score-rules/{id}`: Updates a rule.
- `DELETE /api/v1/score-rules/{id}`: Deletes a rule.

## 7. Candidate ScorePreview
**Structure**:
- `estimatedTotalScore` (Integer)
- `scoreFactorsSummary` (String)

**Behavior**:
- The current implementation only stores global placeholders and textual summaries.
- It lacks fields for `partnerFitScore`, `competitionLevel`, `riskLevel`, and `relationshipStrength`.
- **Decision**: These cannot safely map to canonical criteria and will remain legacy. No automatic translation to canonical `overallScore` will be permitted.

## 8. Approval/Event Integration
- `CandidateApprovedEvent` triggers `ScoreService.handleCandidateApprovedEvent` to write a mocked snapshot.
- No other events (e.g., `ProfileApprovedEvent`, project task submissions, or manual triggers) prompt recalculation or scoring generation.

## 9. Scheduled/Background Jobs
- There are **no scheduled jobs or background processes** that automatically recalculate scores.

## 10. Reports/Dashboard Consumers
- `DashboardService` fetches the latest 10 score snapshots using `findTop10ByOrderByCreatedAtDesc`.
- `ReportService` explicitly pulls `partnerFitScore`, `competitionLevel`, `riskLevel`, and `relationshipStrength` from `ScoreSnapshot`.
- FPT is not explicitly excluded as a target in scoring lists (handled via profile search exclusion only).
- Old score names are heavily embedded in frontend-facing DTOs (`ScoreSnapshotDto`, `CompanyReportItemResponse`). Changes to `ScoreSnapshot` columns will break reporting unless carefully migrated.

## 11. AI Assistant Consumers
- `AssistantContextService` and `OwnerAssistantContextService` both append `Total Score`, `Partner Fit`, `Competition Level`, `Risk Level`, and `Relationship Strength` to the context text block.
- They assume one global score per company (`getLatestScore`).
- They do not distinguish role evaluations.

## 12. Relationship-to-Role Mapping
Official Source: `Project.targetRelationshipType`.
Current Mappings:
- `PARTNER_WITH` -> `PARTNER`
- `POTENTIAL_PARTNER_OF` -> `POTENTIAL_PARTNER`
- `COMPETITOR_OF` -> `COMPETITOR`
- `CUSTOMER_OF` -> `CUSTOMER`
- `SUPPLIER_OF` -> `SUPPLIER`

*Note: AI must never assign the official role. The mapping remains strictly tied to project intent.*

## 13. Legacy Score Field Analysis
The following current fields cannot be safely or mathematically mapped to canonical requirements and must remain **legacy only** (read-only until replacement):
- `partnerFitScore` -> Semantic gap.
- `competitionLevel` -> Semantic gap.
- `riskLevel` -> Semantic gap.
- `relationshipStrength` -> Semantic gap.
- `totalScore` -> Remains legacy read-only. Canonical `overallScore` is added side by side.

## 14. Canonical Criteria Compatibility
No canonical criteria mapping currently exists. Every criterion below represents a **Missing Infrastructure** gap that requires database schema and enum definitions in Phase 1:
- **PARTNER**: `businessValueContributionScore`, `strategicAlignmentScore`, `operationalPerformanceScore`, `capabilityComplementarityScore`, `relationshipQualityScore`, `governanceComplianceScore`.
- **POTENTIAL_PARTNER**: `strategicFitScore`, `capabilityComplementarityScore`, `trustReputationScore`, `financialAttractivenessScore`, `collaborationPotentialScore`, `partnershipRiskScore`.
- **COMPETITOR**: `marketPositionScore`, `productMarketOverlapScore`, `competitiveCapabilityScore`, `strategicIntentScore`, `growthMomentumScore`, `competitiveThreatScore`.
- **CUSTOMER**: `revenueProfitabilityScore`, `purchaseBehaviorScore`, `customerLifetimeValueScore`, `retentionLoyaltyScore`, `growthPotentialScore`, `paymentChurnRiskScore`.
- **SUPPLIER**: `qualityPerformanceScore`, `costCompetitivenessScore`, `deliveryPerformanceScore`, `capacityFlexibilityScore`, `serviceResponsivenessScore`, `supplyRiskComplianceScore`.

## 15. Illustrative AHP Compatibility
AHP weighting structure is entirely missing. Future schemas must support metadata flags:
- `weightingMethod = AHP`
- `weightSource = ILLUSTRATIVE`
- `weightVersion = ILLUSTRATIVE_AHP_V1`

*Constraint: Weights must NOT be classified as EXPERT_APPROVED in this phase.*

## 16. Data-Source Readiness by Role
Not all criteria can be automatically scored from profile documents alone:

| Role | Criterion | Owner Profile | Target Profile | Internal Ops | User Input | Auto Rule Possible? | Current Backend | Missing Infrastructure |
|---|---|---|---|---|---|---|---|---|
| **COMPETITOR** | `productMarketOverlapScore` | YES | YES | NO | NO | YES | NO | Comparison rubric |
| **PARTNER** | `operationalPerformanceScore` | NO | NO | YES | YES | NO | NO | Integration for SLA/KPI |
| **CUSTOMER** | `purchaseBehaviorScore` | NO | NO | YES | YES | NO | NO | Transaction history |
| **SUPPLIER** | `deliveryPerformanceScore` | NO | NO | YES | YES | NO | NO | Delivery records |
| **POTENTIAL_PARTNER** | `financialAttractivenessScore` | NO | YES | NO | YES | YES | NO | Financial baseline rules |

## 17. Null/Completeness Gap
**Target**: `overallScore` is computed only if all required criteria are present; otherwise, it is `null`, and `missingCriteria` is exposed.
**Current Code**: Ignores null checking entirely, mocks values directly, persists them as final, and hides missing component transparency.

## 18. Provenance/Versioning Gap
**Target**: Strict version binding (FPT Profile vX vs Target Profile vY applied against ScoreRule vZ).
**Current Code**: `rule_version` string is hardcoded ("v1.0") and disconnected from any `ScoreRule` entity. No profile version provenance is captured.

## 19. Multi-role Support Gap
**Target**: An independent `ScoreSnapshot` exists per Evaluated Role (e.g., one company can concurrently have a COMPETITOR snapshot and a PARTNER snapshot).
**Current Code**: A company possesses a single stream of global snapshots.

## 20. Recommended Architecture
1. **Dynamic JSON Criteria Model**: Replace flat columns with `criterionScores` (JSON) and `evaluatedRole` (Enum) in `ScoreSnapshot`.
2. **ScoreRuleSet**: Implement a versioned rule set grouping individual criterion configurations per role.
3. **Provenance Validation**: Require and link `targetProfileVersion`, `referenceProfileVersion`, and `scoreRuleVersion` in every snapshot.
4. **Draft Evaluations**: Create an intermediate evaluation state allowing human reviewers to input un-automatable internal operational data before computing `overallScore`.
5. **AHP Calculator Engine**: Construct a dedicated scoring engine that applies rules, checks completeness, handles `COST` inversions, and normalizes final AHP-weighted totals.

## 21. Backward-Compatible Migration Strategy
- `ScoreRule`: **REPLACE_LATER**. Retain current tables to avoid breaking legacy endpoints, introduce `RoleScoreRuleSet` schema independently.
- `ScoreSnapshot`: **EXTEND** & **DEPRECATE**. Keep legacy columns (`partnerFitScore`, etc.) intact for current DTO serialization, but add new nullable JSON structure (`criterionScores`), `evaluatedRole`, and provenance references for Phase 1.
- `CompanyCandidate.ScorePreview`: **LEGACY_READ_ONLY**.
- `Report/Dashboard DTOs`: **EXTEND**. Map new JSON criteria progressively while continuing to serve legacy integers if present.
- `Assistant Context`: **EXTEND**. Include role-specific block dynamically without disrupting current global summary format.

## 22. Files Expected to Change
- `com.apms.domain.score.ScoreSnapshot`
- `com.apms.domain.score.ScoreRule` (or equivalent new entities)
- `com.apms.domain.score.service.ScoreService`
- Database schema scripts (Flyway/Liquibase to be added or JPA schema auto-updates)
- `com.apms.domain.report.service.ReportService`
- `com.apms.domain.assistant.service.AssistantContextService`

## 23. Implementation Phases
*These are recommended phases and must not be implemented during this audit.*
- **Phase 1**: Canonical score-rule and snapshot infrastructure
- **Phase 2**: COMPETITOR criterion input and scoring rubric
- **Phase 3**: POTENTIAL_PARTNER criterion input and scoring rubric
- **Phase 4**: PARTNER criterion input and scoring rubric
- **Phase 5**: CUSTOMER internal operational-data scoring
- **Phase 6**: SUPPLIER internal operational-data scoring
- **Phase 7**: Actual expert AHP pairwise-comparison management
