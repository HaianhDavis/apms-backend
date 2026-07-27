// Pre-migration collision detection
// STOP IF THIS RETURNS > 0
const collisions = db.role_evaluation_drafts.countDocuments({
  $or: [
    { 
      "criterionInputs.capabilityComplementarityScore": { $exists: true },
      "criterionInputs.capabilityAndComplementarityScore": { $exists: true }
    },
    { 
      "criterionInputs.governanceComplianceScore": { $exists: true },
      "criterionInputs.governanceAndRiskScore": { $exists: true }
    }
  ]
});

print("Collisions found: " + collisions);
if (collisions > 0) {
  print("ABORTING MIGRATION: Manually resolve collisions first.");
  quit(1);
}

// Idempotent rename for capabilityComplementarityScore
db.role_evaluation_drafts.updateMany(
  { "criterionInputs.capabilityComplementarityScore": { $exists: true } },
  [
    { $set: { "criterionInputs.capabilityAndComplementarityScore": "$criterionInputs.capabilityComplementarityScore" } },
    { $unset: ["criterionInputs.capabilityComplementarityScore"] }
  ]
);

db.role_evaluation_drafts.updateMany(
  { "automaticSuggestions.capabilityComplementarityScore": { $exists: true } },
  [
    { $set: { "automaticSuggestions.capabilityAndComplementarityScore": "$automaticSuggestions.capabilityComplementarityScore" } },
    { $unset: ["automaticSuggestions.capabilityComplementarityScore"] }
  ]
);

db.role_evaluation_drafts.updateMany(
  { "criterionEvidence.capabilityComplementarityScore": { $exists: true } },
  [
    { $set: { "criterionEvidence.capabilityAndComplementarityScore": "$criterionEvidence.capabilityComplementarityScore" } },
    { $unset: ["criterionEvidence.capabilityComplementarityScore"] }
  ]
);

// Idempotent rename for governanceComplianceScore
db.role_evaluation_drafts.updateMany(
  { "criterionInputs.governanceComplianceScore": { $exists: true } },
  [
    { $set: { "criterionInputs.governanceAndRiskScore": "$criterionInputs.governanceComplianceScore" } },
    { $unset: ["criterionInputs.governanceComplianceScore"] }
  ]
);

db.role_evaluation_drafts.updateMany(
  { "automaticSuggestions.governanceComplianceScore": { $exists: true } },
  [
    { $set: { "automaticSuggestions.governanceAndRiskScore": "$automaticSuggestions.governanceComplianceScore" } },
    { $unset: ["automaticSuggestions.governanceComplianceScore"] }
  ]
);

db.role_evaluation_drafts.updateMany(
  { "criterionEvidence.governanceComplianceScore": { $exists: true } },
  [
    { $set: { "criterionEvidence.governanceAndRiskScore": "$criterionEvidence.governanceComplianceScore" } },
    { $unset: ["criterionEvidence.governanceComplianceScore"] }
  ]
);

// Post-migration legacy-key count
// THIS MUST BE 0
const remainingLegacy = db.role_evaluation_drafts.countDocuments({
  $or: [
    { "criterionInputs.capabilityComplementarityScore": { $exists: true } },
    { "criterionInputs.governanceComplianceScore": { $exists: true } },
    { "automaticSuggestions.capabilityComplementarityScore": { $exists: true } },
    { "automaticSuggestions.governanceComplianceScore": { $exists: true } },
    { "criterionEvidence.capabilityComplementarityScore": { $exists: true } },
    { "criterionEvidence.governanceComplianceScore": { $exists: true } }
  ]
});

print("Remaining legacy keys: " + remainingLegacy);

// Rollback instructions:
// If data is inconsistent, reverse the $set and $unset pipeline arguments in a new query.
