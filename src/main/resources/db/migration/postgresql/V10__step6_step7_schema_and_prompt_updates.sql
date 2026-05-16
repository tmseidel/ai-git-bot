-- Consolidated migration for refactoring steps 6 (native function calling) and
-- 7.1 (plan persistence on agent sessions). Merged from the previous
-- V10..V13 scripts before any of them shipped in a release (last released
-- baseline was V9).
--
-- See the H2 sibling migration for full rationale. PostgreSQL exposes the
-- integer-to-character function as CHR (whereas H2 also accepts CHAR(int))
-- and REGEXP_REPLACE takes the flags as a separate string argument.
--
-- Sections (idempotent, run in order):
--   1) ai_integrations.use_legacy_tool_calling          (former V10)
--   2) agent_sessions.last_plan_summary/json/at         (former V11)
--   3) wrap legacy tool-protocol block in system_prompts (former V12)
--   4) strip the wrapped block                          (former V13)

------------------------------------------------------------------------
-- 1) Native function/tool-calling toggle per AI integration.
------------------------------------------------------------------------
ALTER TABLE ai_integrations
    ADD COLUMN IF NOT EXISTS use_legacy_tool_calling BOOLEAN NOT NULL DEFAULT FALSE;

------------------------------------------------------------------------
-- 2) Persist the latest parsed implementation plan on the agent session.
------------------------------------------------------------------------
ALTER TABLE agent_sessions ADD COLUMN IF NOT EXISTS last_plan_summary VARCHAR(2048);
ALTER TABLE agent_sessions ADD COLUMN IF NOT EXISTS last_plan_json    TEXT;
ALTER TABLE agent_sessions ADD COLUMN IF NOT EXISTS last_plan_at      TIMESTAMP;

------------------------------------------------------------------------
-- 3) Wrap the JSON-in-prompt tool-protocol section of the agent and
--    writer prompts in marker comments so SystemPromptAssembler can
--    include or strip the block depending on the resolved ToolingMode.
------------------------------------------------------------------------

UPDATE system_prompts
SET issue_agent_system_prompt = REPLACE(
        issue_agent_system_prompt,
        '## Output Format',
        '<!-- BEGIN_LEGACY_TOOL_PROTOCOL -->' || CHR(10) || '## Output Format')
WHERE issue_agent_system_prompt IS NOT NULL
  AND issue_agent_system_prompt LIKE '%## Output Format%'
  AND issue_agent_system_prompt NOT LIKE '%BEGIN_LEGACY_TOOL_PROTOCOL%';

UPDATE system_prompts
SET issue_agent_system_prompt = issue_agent_system_prompt
        || CHR(10) || '<!-- END_LEGACY_TOOL_PROTOCOL -->'
WHERE issue_agent_system_prompt IS NOT NULL
  AND issue_agent_system_prompt LIKE '%BEGIN_LEGACY_TOOL_PROTOCOL%'
  AND issue_agent_system_prompt NOT LIKE '%END_LEGACY_TOOL_PROTOCOL%';

UPDATE system_prompts
SET writer_agent_system_prompt = REPLACE(
        writer_agent_system_prompt,
        'Reasoning tools:',
        '<!-- BEGIN_LEGACY_TOOL_PROTOCOL -->' || CHR(10) || 'Reasoning tools:')
WHERE writer_agent_system_prompt IS NOT NULL
  AND writer_agent_system_prompt LIKE '%Reasoning tools:%'
  AND writer_agent_system_prompt NOT LIKE '%BEGIN_LEGACY_TOOL_PROTOCOL%';

UPDATE system_prompts
SET writer_agent_system_prompt = REPLACE(
        writer_agent_system_prompt,
        'Output requirements:',
        '<!-- END_LEGACY_TOOL_PROTOCOL -->' || CHR(10) || CHR(10) || 'Output requirements:')
WHERE writer_agent_system_prompt IS NOT NULL
  AND writer_agent_system_prompt LIKE '%BEGIN_LEGACY_TOOL_PROTOCOL%'
  AND writer_agent_system_prompt NOT LIKE '%END_LEGACY_TOOL_PROTOCOL%';

------------------------------------------------------------------------
-- 4) Strip the wrapped legacy tool-protocol block.
------------------------------------------------------------------------

UPDATE system_prompts
SET issue_agent_system_prompt = REGEXP_REPLACE(
        issue_agent_system_prompt,
        '\s*<!-- BEGIN_LEGACY_TOOL_PROTOCOL -->.*?<!-- END_LEGACY_TOOL_PROTOCOL -->\s*',
        CHR(10),
        'gns'),
    updated_at = CURRENT_TIMESTAMP
WHERE issue_agent_system_prompt IS NOT NULL
  AND issue_agent_system_prompt LIKE '%BEGIN_LEGACY_TOOL_PROTOCOL%'
  AND issue_agent_system_prompt LIKE '%END_LEGACY_TOOL_PROTOCOL%';

UPDATE system_prompts
SET writer_agent_system_prompt = REGEXP_REPLACE(
        writer_agent_system_prompt,
        '\s*<!-- BEGIN_LEGACY_TOOL_PROTOCOL -->.*?<!-- END_LEGACY_TOOL_PROTOCOL -->\s*',
        CHR(10),
        'gns'),
    updated_at = CURRENT_TIMESTAMP
WHERE writer_agent_system_prompt IS NOT NULL
  AND writer_agent_system_prompt LIKE '%BEGIN_LEGACY_TOOL_PROTOCOL%'
  AND writer_agent_system_prompt LIKE '%END_LEGACY_TOOL_PROTOCOL%';

