-- M4 wave 2 (PR-Review Agentic Workflows): seed the "Full-stack QA"
-- workflow_configurations row. See the H2 flavour
-- (db/migration/h2/V18__workflow_configurations_full_stack_qa.sql) for
-- the conceptual notes; the SQL below is portable across H2 and
-- PostgreSQL, so the two files are byte-identical.

INSERT INTO workflow_configurations (name, default_entry, created_at, updated_at)
SELECT 'Full-stack QA', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM workflow_configurations WHERE name = 'Full-stack QA'
);

INSERT INTO workflow_selections (workflow_configuration_id, workflow_key)
SELECT c.id, 'review'
FROM workflow_configurations c
WHERE c.name = 'Full-stack QA'
  AND NOT EXISTS (
      SELECT 1 FROM workflow_selections s
      WHERE s.workflow_configuration_id = c.id
        AND s.workflow_key = 'review'
  );

INSERT INTO workflow_selections (workflow_configuration_id, workflow_key)
SELECT c.id, 'e2e-test'
FROM workflow_configurations c
WHERE c.name = 'Full-stack QA'
  AND NOT EXISTS (
      SELECT 1 FROM workflow_selections s
      WHERE s.workflow_configuration_id = c.id
        AND s.workflow_key = 'e2e-test'
  );

INSERT INTO workflow_selection_params (workflow_selection_id, name, param_value)
SELECT s.id, 'framework', 'playwright'
FROM workflow_selections s
JOIN workflow_configurations c ON c.id = s.workflow_configuration_id
WHERE c.name = 'Full-stack QA'
  AND s.workflow_key = 'e2e-test'
  AND NOT EXISTS (
      SELECT 1 FROM workflow_selection_params p
      WHERE p.workflow_selection_id = s.id AND p.name = 'framework'
  );

INSERT INTO workflow_selection_params (workflow_selection_id, name, param_value)
SELECT s.id, 'maxRetries', '1'
FROM workflow_selections s
JOIN workflow_configurations c ON c.id = s.workflow_configuration_id
WHERE c.name = 'Full-stack QA'
  AND s.workflow_key = 'e2e-test'
  AND NOT EXISTS (
      SELECT 1 FROM workflow_selection_params p
      WHERE p.workflow_selection_id = s.id AND p.name = 'maxRetries'
  );

INSERT INTO workflow_selection_params (workflow_selection_id, name, param_value)
SELECT s.id, 'maxTestCases', '10'
FROM workflow_selections s
JOIN workflow_configurations c ON c.id = s.workflow_configuration_id
WHERE c.name = 'Full-stack QA'
  AND s.workflow_key = 'e2e-test'
  AND NOT EXISTS (
      SELECT 1 FROM workflow_selection_params p
      WHERE p.workflow_selection_id = s.id AND p.name = 'maxTestCases'
  );

