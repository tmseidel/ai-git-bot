-- M2 (PR-Review Agentic Workflows): seed the "Default" workflow_configurations
-- row, enable the built-in 'review' workflow on it, and backfill every bot
-- whose workflow_configuration_id is still null after V14.
--
-- See db/migration/h2/V15__workflow_configurations_default.sql for the
-- conceptual notes; the Postgres flavour differs only in LIMIT syntax.

INSERT INTO workflow_configurations (name, default_entry, created_at, updated_at)
SELECT 'Default', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM workflow_configurations WHERE default_entry = TRUE
);

INSERT INTO workflow_selections (workflow_configuration_id, workflow_key)
SELECT c.id, 'review'
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
    LIMIT 1
)
WHERE workflow_configuration_id IS NULL;

