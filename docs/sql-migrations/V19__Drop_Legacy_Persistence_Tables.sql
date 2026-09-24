-- ============================================================================
-- Migration: V19__Drop_Legacy_Persistence_Tables.sql
-- Description: Drop confirmed obsolete SQL persistence tables:
--              1. ip_whitelist (legacy IP whitelist firewall)
--              2. system_settings (legacy key-value settings)
--              3. fcm_device_tokens (legacy Firebase Cloud Messaging device push tokens)
--              4. partner_contract_approval_syncs (legacy contract approval background sync queue)
--
-- Notes:
--   - Relationship workflow tables (company_relationship_change_proposals,
--     company_relationship_history) are ACTIVE and KEPT.
--   - Contract persistence tables (partner_contracts, partner_contract_versions,
--     partner_contract_clause_versions) are DEFERRED MIGRATION until commercial
--     assessment scoring and insights are migrated to MongoDB ContractResearch.
--   - Hibernate ddl-auto=update does not drop existing physical tables, so this
--     explicit migration drops them from SQL Server.
-- ============================================================================

IF OBJECT_ID('dbo.partner_contract_approval_syncs', 'U') IS NOT NULL
BEGIN
    DROP TABLE dbo.partner_contract_approval_syncs;
    PRINT 'Dropped table dbo.partner_contract_approval_syncs';
END;

IF OBJECT_ID('dbo.fcm_device_tokens', 'U') IS NOT NULL
BEGIN
    DROP TABLE dbo.fcm_device_tokens;
    PRINT 'Dropped table dbo.fcm_device_tokens';
END;

IF OBJECT_ID('dbo.system_settings', 'U') IS NOT NULL
BEGIN
    DROP TABLE dbo.system_settings;
    PRINT 'Dropped table dbo.system_settings';
END;

IF OBJECT_ID('dbo.ip_whitelist', 'U') IS NOT NULL
BEGIN
    DROP TABLE dbo.ip_whitelist;
    PRINT 'Dropped table dbo.ip_whitelist';
END;
