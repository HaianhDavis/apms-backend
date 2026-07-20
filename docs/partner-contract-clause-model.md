# Partner Contract Clause Model

## Overview

The `PartnerContractClauseVersion` entity stores final, approved contract clauses mapped from an AI-assisted extraction process. This model represents the single source of truth for clauses in an approved contract version.

## Core Properties

*   **Identity:** `partnerContractVersionId` and `clauseIdentity` (business key from extraction).
*   **Definition:** `clauseTitle` and `clauseType`.
*   **Source Provenance:** `sourceRawDocumentId`, `evidenceReference`, `sourceExcerpt`.
*   **Integrity:** `clauseHash` to cryptographically link the clause to the exact extracted version it originated from.
*   **Audit:** `approvedByAccountId` and `approvedAt`.

## Typed Fields

Instead of using a generic JSON blob (`normalizedTerms`), the clause version model explicitly defines typed schema fields to enable analytical querying and programmatic evaluation:

### Temporal Constraints
*   `effectiveDate`: When the clause activates.
*   `expiryDate`: When the clause terminates.
*   `noticePeriodDays`: Required notice days for termination/renewal.

### Target Objectives
*   `targetMetricKey`: KPI or SLA metric the clause governs.
*   `targetValue`: The expected value.
*   `targetUnit`: The unit of measurement (e.g., '%', 'days', '$').
*   `comparator`: The condition (e.g., '>=', '<=', '=').
*   `measurementPeriod`: How often it is evaluated (e.g., 'MONTHLY', 'QUARTERLY').

### Penalties
*   `penaltyValue`: The cost or impact if the clause is breached.
*   `penaltyCurrency`: ISO currency code for financial penalties.
*   `penaltyDescription`: Human-readable summary of the consequence.
