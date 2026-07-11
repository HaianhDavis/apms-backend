# FPT Owner Score Snapshot Impact

When evaluating the migration of FPT to become the canonical Owner Organization for APMS, we must carefully handle the transition of historical project score snapshots and relationships.

## Snapshot Preservation Rules

1. **Score Snapshots Are Preserved**
   - Existing score snapshots must remain intact. Under no circumstances should historical performance or benchmarking snapshots be deleted during the transition.
   
2. **No Snapshot Deletion**
   - The migration logic must not include cascading deletes for snapshots when rewiring relationship data.

3. **No Fabrication of Production Counts**
   - The actual production count for FPT's metrics must remain grounded in actual project execution. It must not be manually fabricated or reset as part of the migration.

4. **Handling Existing FPT-as-Target Snapshots**
   - FPT currently exists as a target company (`6a31a0000000000000000001`). Any existing snapshots where FPT was the target of a relationship will be specifically addressed and migrated during the subsequent FPT migration phase (Phase 2), not during the current structural modeling phase.
