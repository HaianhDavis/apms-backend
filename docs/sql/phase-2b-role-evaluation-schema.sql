-- Phase 2B: COMPETITOR Role Evaluation Draft and Approval Workflow
-- Non-destructive schema updates for cross-database idempotency

-- 1. Add idempotency tracking columns to score_snapshots
ALTER TABLE score_snapshots
ADD source_evaluation_draft_id VARCHAR(255) NULL;

ALTER TABLE score_snapshots
ADD approval_idempotency_key VARCHAR(255) NULL;

-- 2. Create unique filtered index to ensure a draft produces exactly one snapshot
CREATE UNIQUE INDEX ux_score_snapshots_source_evaluation_draft
ON score_snapshots(source_evaluation_draft_id)
WHERE source_evaluation_draft_id IS NOT NULL;

-- 3. Create unique filtered index for approval idempotency keys
CREATE UNIQUE INDEX ux_score_snapshots_approval_idempotency_key
ON score_snapshots(approval_idempotency_key)
WHERE approval_idempotency_key IS NOT NULL;
