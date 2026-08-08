# Task Types Documentation

## COMPANY_DATA_PREPARATION
`COMPANY_DATA_PREPARATION` includes the entire end-to-end workflow for preparing company data:
- Document collection
- Document upload
- Manual document input
- AI extraction
- Extracted-data review
- Candidate/profile-update preparation
- Submission

This unified task type replaces the need for a separate document collection task. Documents uploaded during this task are linked to both the `projectId` and `taskId`.

## DOCUMENT_COLLECTION (Deprecated)
`DOCUMENT_COLLECTION` is deprecated and retained temporarily only to read legacy records. No new `DOCUMENT_COLLECTION` tasks can be created. All document collection actions have been migrated to `COMPANY_DATA_PREPARATION`.

## PARTNER_CONTRACT_COLLECTION
`PARTNER_CONTRACT_COLLECTION` remains separate because it manages contracts belonging to an existing Partner relationship. It uses its own dedicated endpoints and logic.
