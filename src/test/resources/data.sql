-- Test-only seed for the mandatory default bot tool configuration.
--
-- Production seeds this through Flyway migration V12. Tests run with
-- spring.flyway.enabled=false and let Hibernate create the schema via
-- ddl-auto=create-drop, so the equivalent rows must be provided here.
-- Keep the tool list in sync with V12/V29/V37.

INSERT INTO bot_tool_configurations (name, default_entry, created_at, updated_at)
SELECT 'Default', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM bot_tool_configurations WHERE default_entry = TRUE
);

INSERT INTO bot_tool_selections (configuration_id, tool_name, tool_kind)
SELECT c.id, v.tool_name, v.tool_kind
FROM bot_tool_configurations c
CROSS JOIN (VALUES
    ('write-file',      'FILE'),
    ('patch-file',      'FILE'),
    ('mkdir',           'FILE'),
    ('delete-file',     'FILE'),
    ('branch-switcher', 'CONTEXT'),
    ('rg',              'CONTEXT'),
    ('find',            'CONTEXT'),
    ('cat',             'CONTEXT'),
    ('git-log',         'CONTEXT'),
    ('git-blame',       'CONTEXT'),
    ('tree',            'CONTEXT'),
    ('ctags-signatures','CONTEXT'),
    ('ctags-deps',      'CONTEXT'),
    ('pr-diff',         'CONTEXT'),
    ('ripgrep',         'CONTEXT'),
    ('grep',            'CONTEXT'),
    ('get-issue',       'REPOSITORY'),
    ('search-issues',   'REPOSITORY')
) AS v(tool_name, tool_kind)
WHERE c.default_entry = TRUE
  AND NOT EXISTS (
      SELECT 1 FROM bot_tool_selections s
      WHERE s.configuration_id = c.id AND s.tool_name = v.tool_name
  );

-- Public-reference counters. Production seeds these through the structured-review
-- migration; here Hibernate creates an empty table, so the rows must be provided too.
INSERT INTO reference_sequences (name, next_value)
SELECT 'workflow_output', 1
WHERE NOT EXISTS (SELECT 1 FROM reference_sequences WHERE name = 'workflow_output');

