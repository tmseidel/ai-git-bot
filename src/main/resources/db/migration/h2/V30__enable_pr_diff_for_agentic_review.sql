-- Enable pr-diff tool for bots using agentic-review workflow
-- The pr-diff tool is required for the compact diff handling in agentic PR reviews.
-- This migration finds all bots that have agentic-review enabled in their workflow
-- configuration and ensures their tool configuration includes the pr-diff tool.

INSERT INTO bot_tool_selections (configuration_id, tool_name, tool_kind)
SELECT DISTINCT btc.id, 'pr-diff', 'CONTEXT'
FROM bot_tool_configurations btc
INNER JOIN bots b ON b.bot_tool_configuration_id = btc.id
INNER JOIN workflow_configurations wc ON wc.id = b.workflow_configuration_id
INNER JOIN workflow_selections ws ON ws.configuration_id = wc.id
WHERE ws.workflow_key = 'agentic-review'
  AND NOT EXISTS (
      SELECT 1 FROM bot_tool_selections s
      WHERE s.configuration_id = btc.id AND s.tool_name = 'pr-diff'
  );
