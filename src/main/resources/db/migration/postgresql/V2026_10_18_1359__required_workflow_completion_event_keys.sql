-- Provisional: qualify schema/application prerequisites and upgrade paths before release.
ALTER TABLE pr_workflow_runs ADD COLUMN required_completion_hash VARCHAR(64);

ALTER TABLE pr_audit_events ADD COLUMN event_key VARCHAR(256);
CREATE UNIQUE INDEX uk_pr_audit_event_key ON pr_audit_events (event_key);

ALTER TABLE event_hook_delivery ADD COLUMN event_key VARCHAR(256);
CREATE UNIQUE INDEX uk_event_hook_delivery_endpoint_event
    ON event_hook_delivery (endpoint_id, event_key);
CREATE INDEX idx_event_hook_delivery_event_key ON event_hook_delivery (event_key);
