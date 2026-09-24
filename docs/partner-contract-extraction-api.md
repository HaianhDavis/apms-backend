# Partner Contract Extraction API

Base Path: `/api/v1/partner-contracts/{contractId}/extractions`
Security: `SYSTEM_ADMIN`, `BUSINESS_DEVELOPMENT_MANAGER`, `BUSINESS_DEVELOPMENT_STAFF`

## 1. Generate Extraction
`POST /`
Generates a new extraction draft from the contract's linked `RawDocument`.

**Response:** `PartnerContractExtractionDraft`

## 2. Review Field
`PATCH /{extractionId}/fields/{fieldKey}`
Reviews an extracted metadata field.

**Request Body (`ReviewExtractionFieldRequest`):**
```json
{
  "reviewDecision": "ACCEPT", // ACCEPT, EDIT, REJECT, NEEDS_MORE_DATA
  "reviewedValue": "Updated Value", // Required if EDIT
  "reviewComment": "Optional context"
}
```

## 3. Review Clause
`PATCH /{extractionId}/clauses/{clauseCandidateId}`
Reviews an extracted clause candidate.

**Request Body (`ReviewExtractionClauseRequest`):**
```json
{
  "reviewDecision": "EDIT",
  "reviewComment": "Modified target value.",
  "clauseTitle": "SLA 1",
  "clauseType": "SLA",
  "targetMetricKey": "uptime",
  "targetValue": "99.9",
  "targetUnit": "%",
  "comparator": ">=",
  "measurementPeriod": "MONTHLY"
}
```

## 4. Apply Extraction
`POST /{extractionId}/apply`
Applies the accepted and edited extraction items to the draft contract in SQL.

**Request Body (`ApplyExtractionRequest`):**
```json
{
  "expectedContractOptimisticVersion": 2,
  "expectedCurrentApprovedVersion": 1,
  "expectedNextApprovalVersion": 2,
  "expectedSourceDocumentHash": "d41d8cd98f00b204e9800998ecf8427e",
  "confirmedOverwriteFieldKeys": ["contractTitle"]
}
```

## 5. Regenerate Extraction
`POST /{extractionId}/regenerate?force=true&comment=New%20Version`
Regenerates an extraction draft, superseding the provided `extractionId`. `force` and `comment` are required if the extraction was already applied.
