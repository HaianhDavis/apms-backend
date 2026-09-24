# Reference Company Context

## 1. Purpose

The `ReferenceCompanyContextService` and its endpoint `GET /api/v1/owner/reference-context` expose a standardized, normalized factual baseline of the active Owner Organization (FPT). This baseline acts as the ground-truth input for future AI comparisons.

**Core Concept:**
```
Owner Company Profile (Factual Data)
+ Target Company Profile (Factual Data)
+ Internal Operational Data
+ Score Rule
= Criterion Scores
= AHP Weighted Overall Score
```

*Note: This phase implements only the **Owner Company Profile** and **Reference Context** components. No criterion scores or AHP weights are calculated or returned by these endpoints.*

## 2. Factual Input Fields

The context directly extracts canonical sections from the approved `CompanyProfile`:
- `companyProfileId`, `profileVersion`
- `legalName`, `tradeName`, `industries`, `businessModel`, `products`, `markets`, `targetCustomers`
- `employeeCount`, `employeeTier`, `revenueTier`, `headquarters`, `website`
- `financial`, `market`, `innovation`, `risk`, `compliance`
- `sourceDocumentIds`

## 3. Data Normalization

The service performs standard data normalization to guarantee a clean baseline for comparisons:
- Strings are trimmed.
- Null or blank list entries are removed.
- Duplicate list entries are removed.
- Meaningful list order and `BigDecimal` precision are strictly preserved.
- No facts are inferred or fabricated to fill missing data.

## 4. Profile Version Provenance

The context always requires and includes the exact `profileVersion` of the owner profile and validates that a corresponding `CompanyProfileVersion` snapshot exists. This ensures that any future comparison can be traced back to the exact historical state of the FPT baseline at that moment.

## 5. Comparison Input Availability

The context response includes a `comparisonInputAvailability` structure. This maps key future comparison areas (e.g., `strategicFit`, `capabilityComplementarity`) to their readiness state based solely on data existence.

**Difference between Availability and Score:**
This is an availability metadata flag (`true/false`) alongside a list of required and missing factual fields. It **is not** a role score or an evaluation of FPT's strength.

**Supported Availability Checks:**
1. **strategicFit**: Requires `business.industries`, `business.products`, `business.markets`.
2. **capabilityComplementarity**: Requires `innovation.technologyCapabilities`.
3. **productMarketOverlap**: Requires `business.products`, `business.markets`.
4. **competitiveCapabilityComparison**: Requires `innovation.technologyCapabilities` AND at least one size metric (`companySize.employeeCount` OR `companySize.employeeTier`).
5. **marketPositionComparison**: Requires at least one of `market.marketShare`, `market.brandRank`, `market.clientCount`.
6. **financialComparison**: Requires at least one of `financial.revenue`, `financial.profitMargin`, `financial.revenueGrowth`.
7. **complianceComparison**: Requires at least one of `compliance.status`, `compliance.qualityCertifications`, `compliance.securityCertifications`.

Missing optional fields do not block the context from returning, but simply set the relevant availability flag to `false`.
