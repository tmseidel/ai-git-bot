-- M2 (PR-Review Agentic Workflows): seed the "Default" workflow_configurations
-- row, enable the built-in 'review' workflow on it, and backfill every bot
-- whose workflow_configuration_id is still null after V14.
--
-- This migration is the self-contained counterpart of V14 (tables + FK). It
-- supersedes the previous DefaultWorkflowConfigurationInitializer ApplicationRunner
-- so that the default-config bootstrap is deterministic, observable in the
-- schema history table, and survives without any startup-time Java logic.
--
-- Steps (idempotent, order matters):
--   1) Insert the Default workflow_configurations row when none is flagged
--      default_entry (guarded by WHERE NOT EXISTS).
--   2) Enable the only REVIEW-category workflow shipped with the application
--      ('review') on that default configuration. Future REVIEW workflows must
--      be added by their own follow-up Flyway script — the application does
--      NOT auto-extend the Default at runtime, mirroring the policy used by
--      bot_tool_configurations / bot_tool_selections in V11/V12.
--   3) Backfill bots.workflow_configuration_id for every row that still has
--      NULL after the V14 column addition, so existing bots keep behaving
--      exactly as before (running the 'review' workflow only).

INSERT INTO workflow_configurations (name, default_entry, created_at, updated_at)
SELECT 'Default', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM workflow_configurations WHERE default_entry = TRUE
);

INSERT INTO workflow_selections (workflow_configuration_id, workflow_key, params_json)
SELECT c.id, 'review', NULL
FROM workflow_configurations c
WHERE c.default_entry = TRUE
  AND NOT EXISTS (
      SELECT 1 FROM workflow_selections s
      WHERE s.workflow_configuration_id = c.id
        AND s.workflow_key = 'review'
  );

UPDATE bots
SET workflow_configuration_id = (
    SELECT id FROM workflow_configurations
    WHERE default_entry = TRUE
    FETCH FIRST 1 ROWS ONLY
)
WHERE workflow_configuration_id IS NULL;

