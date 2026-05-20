-- M4 wave 2 (PR-Review Agentic Workflows): seed the "Full-stack QA"
-- workflow_configurations row. This is the canonical opt-in profile for
-- operators that want the LLM-driven E2E test workflow on top of the
-- standard review pass.
--
-- Unlike V15 ("Default"), this configuration is NOT marked default_entry,
-- so it never auto-attaches to existing bots. Operators must explicitly
-- pick it from the bot form. The migration is idempotent: re-running it
-- on a database that already carries the row (or where an operator has
-- since added/removed selections by hand) leaves data alone.
--
-- The seeded selections are:
--   * 'review'    – the standard review workflow, no params override.
--   * 'e2e-test'  – the LLM-driven E2E workflow with conservative cost
--                   guards (framework=playwright, maxRetries=1,
--                   maxTestCases=10). Operators can tune the params via
--                   the workflow-configuration UI without re-running the
--                   migration.

INSERT INTO workflow_configurations (name, default_entry, created_at, updated_at)
SELECT 'Full-stack QA', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM workflow_configurations WHERE name = 'Full-stack QA'
);

INSERT INTO workflow_selections (workflow_configuration_id, workflow_key, params_json)
SELECT c.id, 'review', NULL
FROM workflow_configurations c
WHERE c.name = 'Full-stack QA'
  AND NOT EXISTS (
      SELECT 1 FROM workflow_selections s
      WHERE s.workflow_configuration_id = c.id
        AND s.workflow_key = 'review'
  );

INSERT INTO workflow_selections (workflow_configuration_id, workflow_key, params_json)
SELECT c.id, 'e2e-test',
       '{"framework":"playwright","maxRetries":1,"maxTestCases":10}'
FROM workflow_configurations c
WHERE c.name = 'Full-stack QA'
  AND NOT EXISTS (
      SELECT 1 FROM workflow_selections s
      WHERE s.workflow_configuration_id = c.id
        AND s.workflow_key = 'e2e-test'
  );

