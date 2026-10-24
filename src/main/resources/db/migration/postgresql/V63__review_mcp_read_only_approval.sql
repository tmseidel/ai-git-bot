-- Provisional: release requires the integrated and qualified V59-V62 prefix.
ALTER TABLE mcp_configurations ADD COLUMN review_approval_revision VARCHAR(36) DEFAULT (gen_random_uuid()::text) NOT NULL;
ALTER TABLE mcp_selected_tools ADD COLUMN review_read_only_approved BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE mcp_selected_tools ADD COLUMN review_approval_schema_hash VARCHAR(64);
ALTER TABLE mcp_selected_tools ADD COLUMN review_approval_configuration_hash VARCHAR(64);
