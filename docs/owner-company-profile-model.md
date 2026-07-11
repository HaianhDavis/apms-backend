# Owner Company Profile Model Architecture

This document describes the canonical factual extensions and the Owner Organization model for APMS.

## Centralized Owner Resolution
- **OwnerOrganizationService**: A centralized service replaces hardcoded constants (e.g., `OWNER_ORG_COMPANY_ID`) across the repository.
- **Transitional State**: FPT migration is deferred. The configured owner ID remains `6a31a0000000000000000000` via Spring properties (`apms.owner.company-profile-id`) to preserve current demo data behavior.
- **Graph Direction**: Relationships consistently flow from Owner -> Target.
- **CompanyRole**: The `CompanyRole` enum does NOT contain an `OWNER` role, as ownership is a structural graph anchor, not an external relationship label.
- **Role Scoring**: Role scoring is strictly prohibited and not implemented.

## Reusable Factual Model Package
The following factual domain models (`com.apms.domain.company.model`) have been introduced and applied symmetrically across `CompanyProfile`, `CompanyCandidate`, `CompanyProfileUpdateProposal`, and AI extraction models.

### Canonical Factual Field Paths
All factual model objects are fully nullable for backward compatibility with existing MongoDB documents.

- **financial**
  - `financial.revenue` (BigDecimal)
  - `financial.revenueCurrency` (String)
  - `financial.revenueGrowth` (BigDecimal)
  - `financial.debtRatio` (BigDecimal)
  - `financial.profitMargin` (BigDecimal)
  - `financial.fundingStage` (String)
  - `financial.profitability` (String)

- **market**
  - `market.marketShare` (BigDecimal)
  - `market.brandRank` (Integer)
  - `market.clientCount` (Long)
  - `market.mainMarkets` (List<String>)

- **innovation**
  - `innovation.patents` (Integer)
  - `innovation.rdInvestmentPercent` (BigDecimal)
  - `innovation.techStack` (List<String>)
  - `innovation.techMaturityLevel` (Integer)
  - `innovation.productInnovationRate` (BigDecimal)
  - `innovation.technologyCapabilities` (List<String>)

- **risk**
  - `risk.legalRisk` (String)
  - `risk.financialRisk` (String)
  - `risk.reputationRisk` (String)
  - `risk.securityRisk` (String)
  - `risk.conflictOfInterestRisk` (String)
  - `risk.supplyInterruptionRisk` (String)
  - `risk.dependencyRisk` (String)
  - `risk.overallRiskLevel` (String)

- **compliance**
  - `compliance.status` (String)
  - `compliance.qualityCertifications` (List<String>)
  - `compliance.securityCertifications` (List<String>)
  - `compliance.antiCorruptionPolicy` (String)
  - `compliance.laborCompliance` (String)
  - `compliance.environmentalPolicy` (String)

## Numeric and Percentage Conventions
- **Percentage Values**: All percentage properties (e.g. `revenueGrowth`, `profitMargin`, `marketShare`, `rdInvestmentPercent`, `productInnovationRate`) are recorded in **percentage points**. For example, 12.5 means 12.5%.
- Values like `revenueGrowth` and `profitMargin` can be negative.
- No universal 0-100 constraint is applied across all percentage fields.

## Lifecycle Parity and Behavior
1. **AI Extraction QA Behavior**: Extractions populate these nested objects via dotted paths in `fieldResults`. The `ExtractionMergeService` ensures only fields with `REVIEWED` status are merged.
2. **CompanyProfile / CompanyCandidate Parity**: Candidate approval maps all 5 factual model segments directly into the final `CompanyProfile`.
3. **Update Proposal Behavior**: `CompanyProfileUpdateProposal` stores these nested segments explicitly (`proposedFinancial`, etc.) and the `ProjectTaskSubmissionService` handles merging them selectively into existing profiles.
4. **Profile Version Behavior**: `CompanyProfileVersion` preserves the entire object state, inherently preserving these new factual models across update snapshots.
