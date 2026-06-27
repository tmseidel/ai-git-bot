-- Add ctags-signatures and ctags-deps context tools (code-base structure extraction)
-- to the default tool configuration so they are available to all bots immediately.
-- Avoids duplicate inserts via NOT EXISTS guard.

INSERT INTO bot_tool_selections (configuration_id, tool_name, tool_kind)
SELECT c.id, v.tool_name, v.tool_kind
FROM bot_tool_configurations c
CROSS JOIN (VALUES
    ('ctags-signatures', 'CONTEXT'),
    ('ctags-deps',       'CONTEXT')
) AS v(tool_name, tool_kind)
WHERE c.default_entry = TRUE
  AND NOT EXISTS (
      SELECT 1 FROM bot_tool_selections s
      WHERE s.configuration_id = c.id AND s.tool_name = v.tool_name
  );
