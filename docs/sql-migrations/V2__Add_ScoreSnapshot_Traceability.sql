-- V2__Add_ScoreSnapshot_Traceability.sql
-- Manual SQL Server migration to add immutable RoleEvaluationVersion linkages to ScoreSnapshot
-- Applies to: POTENTIAL_PARTNER, CUSTOMER, SUPPLIER scoring records.
-- Note: Historical rows and COMPETITOR snapshots will have null values (Legacy Partial / Draft Based).

ALTER TABLE score_snapshots
ADD approved_role_evaluation_version_id VARCHAR(255) NULL;

ALTER TABLE score_snapshots
ADD approved_role_evaluation_version_number INT NULL;
