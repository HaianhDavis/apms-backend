// This script backfills the documentVersion field required by Spring Data MongoDB @Version optimistic locking.
// Legacy documents without documentVersion cannot be safely updated by Spring Data after adding the @Version annotation.
// Run this directly against the APMS MongoDB database BEFORE deploying the new backend version.

db.company_candidates.updateMany(
    { documentVersion: { $exists: false } },
    { $set: { documentVersion: NumberLong(0) } }
);

db.company_profile_update_proposals.updateMany(
    { documentVersion: { $exists: false } },
    { $set: { documentVersion: NumberLong(0) } }
);

print("Backfill complete.");
