# AI Extraction Quality Assurance and Evaluation Framework

## A. Problem Statement

During the APMS product review, the committee raised a critical concern:

> "If the system simply allows users to upload a document and then blindly trusts whatever AI returns, the extracted data has no practical reliability. AI may hallucinate, miss fields, or produce information that is not supported by the source document."

**Core issue**: Accepting AI extraction results without evidence, validation, or human review is irresponsible and undermines the trustworthiness of official business profiles built from that data.

---

## B. Solution

APMS treats AI extraction output as **draft data only**. AI-extracted information is never treated as ground truth.

Every AI extraction result must go through a multi-stage quality pipeline before it can be used to generate official business profiles:

| Stage | Description |
|-------|-------------|
| **Standardized Prompt** | All AI providers use the same externalized prompt template that enforces structured output with field-level evidence |
| **Field-Level Evidence** | Each extracted field includes `evidenceText` (the supporting text from the document), `confidence` score, and optional `pageNumber` |
| **Backend Validation** | `AiExtractionQualityService` applies deterministic rules: evidence checks, format validation (email, URL, tax code), confidence thresholds |
| **Quality Metrics** | Automated metrics computed per extraction: `completenessRate`, `evidenceCoverageRate`, `hallucinationRiskCount`, `failedFields`, `warningFields`, `averageConfidence` |
| **Staff Review** | Staff reviews each field (ACCEPT / EDIT / REJECT / NEEDS_REVIEW) through the workbench UI |
| **Review Completion** | Staff marks the extraction as REVIEWED only after all critical fields pass validation or are explicitly reviewed |
| **Draft Generation Guard** | `ExtractionMergeService` blocks draft generation unless all selected extractions have `qualityStatus = REVIEWED` |
| **Manager Approval** | Manager reviews the submitted draft before it becomes an official CompanyProfile |

---

## C. Prompt Template vs. Test Dataset

### Prompt Template (`company-extraction.prompt.md`)
- **Purpose**: Guides AI during runtime extraction
- **Used by**: `GeminiExtractionProvider` and `OpenAiExtractionProvider`
- **Contains**: Extraction schema, field definitions, evidence requirements, output format rules
- **Rules enforced**:
  - AI must provide `evidenceText` for every extracted field
  - AI must NOT produce relationship classification or recommendations
  - AI must output factual, evidence-backed JSON only
  - AI must use `null` for unknown values, not invented data

### Test Dataset (sample documents + expected JSON)
- **Purpose**: Used only for offline testing/evaluation
- **NOT sent to AI**: Expected JSON files are comparison targets only
- **Used by**: Evaluation tests to compare actual AI output against known-good baselines
- **Contains**: Sample business documents and their manually verified expected extraction results

> **Critical Rule**: Expected JSON must NEVER be sent to the AI model. It exists solely as a test comparison target.

---

## D. Runtime Flow

```
┌──────────────────────────────────────────────────────────────────────┐
│                        RUNTIME EXTRACTION FLOW                       │
│                                                                      │
│  1. Staff uploads document (PDF/text/manual entry)                   │
│     └──> ImportJob created, RawDocument stored                       │
│                                                                      │
│  2. Staff triggers AI extraction                                     │
│     └──> POST /api/v1/import-jobs/{id}/ai-extractions                │
│     └──> AI provider loads externalized prompt template               │
│     └──> AI returns structured JSON with evidence per field           │
│     └──> AiExtractionResponseMapper parses nested or flat schema      │
│     └──> AiExtractionQualityService validates fields                  │
│     └──> Quality metrics computed                                     │
│     └──> AiExtractionCache saved with:                                │
│           - extractedData (flat, backward-compatible)                 │
│           - fieldResults (per-field QA metadata)                      │
│           - qualityMetrics                                            │
│           - qualityStatus (VALIDATED / NEEDS_REVIEW)                  │
│                                                                      │
│  3. Staff reviews extraction in workbench                             │
│     └──> GET /ai-extractions/{id}/quality                             │
│     └──> For each field:                                              │
│          └──> PATCH /ai-extractions/{id}/fields/{field}/review        │
│          └──> Actions: ACCEPTED / EDITED / REJECTED / NEEDS_REVIEW    │
│                                                                      │
│  4. Staff completes review                                            │
│     └──> POST /ai-extractions/{id}/review/complete                    │
│     └──> System checks: all critical fields must be reviewed          │
│     └──> qualityStatus set to REVIEWED                                │
│                                                                      │
│  5. Staff selects reviewed extraction(s) to generate draft            │
│     └──> ExtractionMergeService BLOCKS if any extraction is not       │
│          REVIEWED                                                     │
│     └──> EDITED values used; REJECTED fields excluded                 │
│     └──> CompanyCandidate or ProfileUpdateProposal draft created      │
│                                                                      │
│  6. Staff submits selected draft                                      │
│     └──> One-active-submission rule enforced                          │
│     └──> Task status changes to IN_REVIEW                             │
│                                                                      │
│  7. Manager reviews and approves                                      │
│     └──> Draft becomes official CompanyProfile                        │
└──────────────────────────────────────────────────────────────────────┘
```

---

## E. Testing / Evaluation Flow

```
┌──────────────────────────────────────────────────────────────────────┐
│                       EVALUATION FLOW (OFFLINE)                      │
│                                                                      │
│  1. Load sample document                                             │
│  2. Run AI extraction (or use cached extraction result)              │
│  3. Compare actual extracted JSON with expected JSON                  │
│  4. Compute quality metrics:                                         │
│     - fieldAccuracy (actual vs. expected per field)                   │
│     - completenessRate (required fields present)                     │
│     - evidenceCoverageRate (fields with evidence / fields with value) │
│     - hallucinationRiskCount (fields with value but no evidence)     │
│     - warningFields / failedFields / passedFields                    │
│     - averageConfidence                                              │
│  5. Assert thresholds met                                            │
│                                                                      │
│  Expected JSON is NEVER sent to the AI.                              │
│  It is used ONLY as a comparison target.                             │
└──────────────────────────────────────────────────────────────────────┘
```

---

## F. Quality Metrics Reference

| Metric | Type | Description |
|--------|------|-------------|
| `totalFields` | int | Total number of fields in the extraction |
| `fieldsWithValue` | int | Fields that have a non-null, non-empty value |
| `fieldsWithEvidence` | int | Fields that have a non-empty `evidenceText` |
| `passedFields` | int | Fields that passed validation |
| `warningFields` | int | Fields with validation warnings (e.g., no evidence for non-critical fields, low confidence) |
| `failedFields` | int | Fields that failed validation (e.g., critical field without evidence, invalid format) |
| `averageConfidence` | Double | Average AI confidence score across fields with confidence values |
| `evidenceCoverageRate` | Double | `fieldsWithEvidence / fieldsWithValue` — measures how much of the extraction is evidence-backed |
| `completenessRate` | Double | Proportion of required fields (legalName, industries, description) that are present |
| `hallucinationRiskCount` | int | Fields with value but no `evidenceText` — potential hallucinations |

### Validation Rules

| Rule | Applies To | Result |
|------|-----------|--------|
| Critical field without evidence | `legalName`, `taxCode` | **FAIL** |
| Non-critical field without evidence | All others | **WARNING** |
| Invalid email format | `email` | **FAIL** |
| Invalid URL format | `website` | **FAIL** |
| Tax code shorter than 5 characters | `taxCode` | **FAIL** |
| Confidence < 0.5 | Any field | **WARNING** |
| Null or empty value | Any field | **PASS** (nothing to validate) |

---

## G. Defense Statement

> **"The system does not blindly trust AI output. AI extraction is treated as draft data with field-level evidence requirements, automated validation rules, quality metrics scoring, and mandatory human review. Only after a staff member reviews each field and a manager approves the resulting draft can extracted data become part of an official business profile."**

### Key architectural safeguards:

1. **No relationship classification**: The AI prompt explicitly prohibits generating relationship type suggestions. Relationship types are decided by the Business Development Manager, not by AI.

2. **Backward-compatible data model**: The existing flat `ExtractedCompanyData` structure is preserved for downstream compatibility. New QA metadata (`fieldResults`, `qualityMetrics`, `qualityStatus`) is stored alongside it in `AiExtractionCache`.

3. **Strict draft generation guard**: `ExtractionMergeService` checks `qualityStatus == REVIEWED` on every selected extraction before allowing draft creation. This guard is enforced at the service layer and cannot be bypassed by the API.

4. **Reviewed value priority**: During merge, the system uses EDITED values when a staff member has corrected a field, ACCEPTED values when approved as-is, and excludes REJECTED fields entirely.

5. **Expected JSON isolation**: Test/evaluation expected JSON files are never loaded or sent to AI providers. They exist solely as offline comparison targets for quality evaluation tests.

---

## API Endpoints

### Quality Information
```
GET /api/v1/ai-extractions/{extractionId}/quality
```
Returns the full `AiExtractionCache` including `fieldResults`, `qualityMetrics`, and `qualityStatus`.

### Field-Level Review
```
PATCH /api/v1/ai-extractions/{extractionId}/fields/{fieldName}/review
```
Body:
```json
{
  "reviewStatus": "ACCEPTED | EDITED | REJECTED | NEEDS_REVIEW",
  "reviewedValue": "optional corrected value (required for EDITED)",
  "comment": "optional review comment"
}
```

### Complete Review
```
POST /api/v1/ai-extractions/{extractionId}/review/complete
```
Sets `qualityStatus = REVIEWED` after validating that all critical fields have been reviewed. Blocks if any critical field has `NEEDS_REVIEW` status or `PENDING` status with `FAIL` validation.

---

## File References

| File | Purpose |
|------|---------|
| `ai-prompts/company-extraction.prompt.md` | Externalized AI prompt template |
| `AiExtractionQualityService.java` | Validation and metrics computation |
| `AiExtractionResponseMapper.java` | Parses AI output (nested or flat schema) |
| `ExtractionFieldResult.java` | Per-field QA metadata wrapper |
| `ExtractionQualityMetrics.java` | Aggregated quality metrics |
| `ExtractionQualityStatus.java` | Enum: PENDING_VALIDATION, VALIDATED, NEEDS_REVIEW, REVIEWED, FAILED |
| `ExtractionValidationStatus.java` | Enum: PASS, WARNING, FAIL, NOT_CHECKED |
| `ExtractionReviewStatus.java` | Enum: PENDING, ACCEPTED, EDITED, REJECTED, NEEDS_REVIEW |
| `ExtractionReviewRequest.java` | Staff review request DTO |
| `AiExtractionCache.java` | MongoDB entity with both flat data and QA metadata |
| `ExtractionMergeService.java` | Draft generation with REVIEWED guard |
| `AiController.java` | REST endpoints for quality, review, and completion |
