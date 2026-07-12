# Canonical Role-Scoring API Reference (Phase 1)

## Legacy Endpoints (Preserved — Unchanged)

### GET /api/v1/profiles/{companyId}/scores
Returns legacy `ScoreSnapshotDto` list for a company. Only legacy snapshots (`evaluatedRole IS NULL`).

### GET /api/v1/score-rules
Returns all legacy `ScoreRuleDto` entries.

### POST /api/v1/score-rules
Creates a legacy score rule.

### PUT /api/v1/score-rules/{id}
Updates a legacy score rule.

### DELETE /api/v1/score-rules/{id}
Deletes a legacy score rule.

---

## Canonical Read Endpoints (New — Read-Only)

### GET /api/v1/profiles/{companyProfileId}/role-scores

Returns canonical role-scoring snapshots for a target company.

**Query Parameters:**
| Parameter | Type | Required | Description |
|---|---|---|---|
| `role` | `CompanyRole` | No | Filter by role (e.g., `COMPETITOR`, `PARTNER`) |

**Rules:**
- Returns only canonical snapshots (`evaluatedRole IS NOT NULL`).
- Ordered newest first by `calculatedAt`.
- Rejects `companyProfileId` equal to the configured Owner Organization.

**Example Response (Complete):**
```json
{
  "status": "success",
  "data": [
    {
      "id": 42,
      "targetCompanyProfileId": "6a31a0000000000000000002",
      "targetProfileVersion": 1,
      "referenceCompanyProfileId": "6a31a0000000000000000001",
      "referenceProfileVersion": 1,
      "evaluatedRole": "COMPETITOR",
      "criterionScores": {
        "marketPositionScore": 78,
        "productMarketOverlapScore": 65,
        "competitiveCapabilityScore": 85,
        "strategicIntentScore": 70,
        "growthMomentumScore": 82,
        "competitiveThreatScore": 88
      },
      "normalizedCriterionScores": {
        "marketPositionScore": 78,
        "productMarketOverlapScore": 65,
        "competitiveCapabilityScore": 85,
        "strategicIntentScore": 70,
        "growthMomentumScore": 82,
        "competitiveThreatScore": 88
      },
      "weightsUsed": {
        "marketPositionScore": 0.20,
        "productMarketOverlapScore": 0.16,
        "competitiveCapabilityScore": 0.20,
        "strategicIntentScore": 0.11,
        "growthMomentumScore": 0.09,
        "competitiveThreatScore": 0.24
      },
      "overallScore": 79.90,
      "completenessStatus": "COMPLETE",
      "missingCriteria": [],
      "scoreRuleSetVersion": "ROLE_SCORING_V1",
      "weightVersion": "ILLUSTRATIVE_AHP_V1",
      "weightingMethod": "AHP",
      "weightSource": "ILLUSTRATIVE",
      "calculatedAt": "2026-07-12T05:30:00Z"
    }
  ]
}
```

**Example Response (Incomplete):**
```json
{
  "status": "success",
  "data": [
    {
      "id": 43,
      "targetCompanyProfileId": "6a31a0000000000000000002",
      "targetProfileVersion": 1,
      "referenceCompanyProfileId": "6a31a0000000000000000001",
      "referenceProfileVersion": 1,
      "evaluatedRole": "COMPETITOR",
      "criterionScores": {
        "marketPositionScore": 78,
        "productMarketOverlapScore": null,
        "competitiveCapabilityScore": 85,
        "strategicIntentScore": 70,
        "growthMomentumScore": 82,
        "competitiveThreatScore": 88
      },
      "normalizedCriterionScores": {
        "marketPositionScore": 78,
        "productMarketOverlapScore": null,
        "competitiveCapabilityScore": 85,
        "strategicIntentScore": 70,
        "growthMomentumScore": 82,
        "competitiveThreatScore": 88
      },
      "weightsUsed": {
        "marketPositionScore": 0.20,
        "productMarketOverlapScore": 0.16,
        "competitiveCapabilityScore": 0.20,
        "strategicIntentScore": 0.11,
        "growthMomentumScore": 0.09,
        "competitiveThreatScore": 0.24
      },
      "overallScore": null,
      "completenessStatus": "INCOMPLETE",
      "missingCriteria": ["productMarketOverlapScore"],
      "scoreRuleSetVersion": "ROLE_SCORING_V1",
      "weightVersion": "ILLUSTRATIVE_AHP_V1",
      "weightingMethod": "AHP",
      "weightSource": "ILLUSTRATIVE",
      "calculatedAt": "2026-07-12T05:30:00Z"
    }
  ]
}
```

---

### GET /api/v1/role-score-rule-sets

Returns canonical role-score rule set configurations.

**Query Parameters:**
| Parameter | Type | Required | Description |
|---|---|---|---|
| `role` | `CompanyRole` | No | Filter by role |
| `active` | `Boolean` | No | Filter by active status |

**Response includes:** Rule set metadata and ordered criterion rules with weights, directions, and required flags.

---

## Errors

| Condition | Message |
|---|---|
| Owner as target | `The Owner Organization cannot be evaluated as a target company.` |
| Unknown criterion | `Unknown criterion for evaluated role: {criterionKey}` |
| Invalid score range | `Criterion score must be between 0 and 100: {criterionKey}` |
| Missing rule set | `No active score rule set was found for role: {role}` |
| Reference mismatch | `Reference company must be the configured Owner Organization.` |
| Missing target version | `Target CompanyProfile version snapshot was not found.` |
| Missing reference version | `Reference CompanyProfile version snapshot was not found.` |

---

## Permissions

Canonical read endpoints follow the existing application security convention. No special canonical-specific roles are required in Phase 1.

---

## No Public Score Submission API

Phase 1 does not expose a public endpoint for creating canonical snapshots. The `CanonicalScoreSnapshotService` is an internal service only. A public calculation endpoint will be introduced when criterion rubrics are implemented.
