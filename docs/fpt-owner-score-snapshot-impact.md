# FPT Owner Score Snapshot Impact

## Current Behavior
The APMS application evaluates Target Companies using AHP scoring resulting in a `ScoreSnapshot`. 
Previously, FPT was evaluated as a target company.

## Migration Impact
1. **Preservation**: Historical score snapshots targeting FPT (`6a31a0000000000000000001`) are intentionally preserved. They serve as legacy evaluation records and must not be mutated or deleted.
2. **Cessation**: `AssistantDemoDataSeeder` has been modified to stop creating any new score snapshots targeting FPT. FPT is now the active Owner Organization.
3. **Filtering**: Reporting and AI assistant views querying target company lists naturally filter out the configured `apms.owner.company-profile-id`. FPT will no longer appear in views intended to show "external evaluated companies".
4. **No Role Scoring**: The Owner Organization is not a "CompanyRole" like "COMPETITOR" or "SUPPLIER". FPT is the baseline from which these roles are viewed. No specific scoring logic is added to the Owner itself in this phase.
