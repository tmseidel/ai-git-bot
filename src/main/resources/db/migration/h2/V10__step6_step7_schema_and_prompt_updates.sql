-- Consolidated migration for refactoring steps 6 (native function calling) and
-- 7.1 (plan persistence on agent sessions). Merged from the previous
-- V10..V13 scripts before any of them shipped in a release (last released
-- baseline was V9).
--
-- Sections (idempotent, run in order):
--   1) ai_integrations.use_legacy_tool_calling          (former V10)
--   2) agent_sessions.last_plan_summary/json/at         (former V11)
--   3) wrap legacy tool-protocol block in system_prompts with
--      <!-- BEGIN/END_LEGACY_TOOL_PROTOCOL --> markers   (former V12)
--   4) strip the wrapped block so the operator-editable prompt only carries
--      the mode-neutral role description; SystemPromptAssembler appends the
--      transport-specific protocol at runtime                  (former V13)
--
-- Sections 3 + 4 are kept as two passes (instead of a single regex over the
-- original anchors) so the logic stays identical to the reviewed scripts and
-- robust against operator-edited prompts that may already contain partial
-- markers.

------------------------------------------------------------------------
-- 1) Native function/tool-calling toggle per AI integration.
--    Default = false (use tools-api). Operators can switch back to the
--    legacy JSON-in-prompt path per integration through the admin UI.
------------------------------------------------------------------------
ALTER TABLE ai_integrations ADD COLUMN IF NOT EXISTS use_legacy_tool_calling BOOLEAN NOT NULL DEFAULT FALSE;

------------------------------------------------------------------------
-- 2) Persist the latest parsed implementation plan on the agent session
--    so PR-body and follow-up comment generation no longer need to
--    re-parse the entire conversation history.
------------------------------------------------------------------------
ALTER TABLE agent_sessions ADD COLUMN IF NOT EXISTS last_plan_summary VARCHAR(2048);
ALTER TABLE agent_sessions ADD COLUMN IF NOT EXISTS last_plan_json    CLOB;
ALTER TABLE agent_sessions ADD COLUMN IF NOT EXISTS last_plan_at      TIMESTAMP;

------------------------------------------------------------------------
-- 3) Wrap the JSON-in-prompt tool-protocol section of the agent and
--    writer prompts in marker comments so SystemPromptAssembler can
--    include or strip the block depending on the resolved ToolingMode.
--
--    Native function calling sends tool descriptors through the provider
--    API; the legacy {runTools, requestTools, requestFiles} guidance
--    becomes contradictory noise in that mode.
--
--    Idempotent: only adds a marker when the matching anchor exists and
--    the marker is not already present.
------------------------------------------------------------------------

-- issue_agent_system_prompt: legacy block starts at "## Output Format" and
-- runs until end-of-string in V3/V9.
UPDATE system_prompts
SET issue_agent_system_prompt = REPLACE(
        issue_agent_system_prompt,
        '## Output Format',
        '<!-- BEGIN_LEGACY_TOOL_PROTOCOL -->' || CHAR(10) || '## Output Format')
WHERE issue_agent_system_prompt IS NOT NULL
  AND issue_agent_system_prompt LIKE '%## Output Format%'
  AND issue_agent_system_prompt NOT LIKE '%BEGIN_LEGACY_TOOL_PROTOCOL%';

UPDATE system_prompts
SET issue_agent_system_prompt = issue_agent_system_prompt
        || CHAR(10) || '<!-- END_LEGACY_TOOL_PROTOCOL -->'
WHERE issue_agent_system_prompt IS NOT NULL
  AND issue_agent_system_prompt LIKE '%BEGIN_LEGACY_TOOL_PROTOCOL%'
  AND issue_agent_system_prompt NOT LIKE '%END_LEGACY_TOOL_PROTOCOL%';

-- writer_agent_system_prompt: legacy block sits between the
-- "Reasoning tools:" header (V5) and the still-relevant
-- "Output requirements:" section.
UPDATE system_prompts
SET writer_agent_system_prompt = REPLACE(
        writer_agent_system_prompt,
        'Reasoning tools:',
        '<!-- BEGIN_LEGACY_TOOL_PROTOCOL -->' || CHAR(10) || 'Reasoning tools:')
WHERE writer_agent_system_prompt IS NOT NULL
  AND writer_agent_system_prompt LIKE '%Reasoning tools:%'
  AND writer_agent_system_prompt NOT LIKE '%BEGIN_LEGACY_TOOL_PROTOCOL%';

UPDATE system_prompts
SET writer_agent_system_prompt = REPLACE(
        writer_agent_system_prompt,
        'Output requirements:',
        '<!-- END_LEGACY_TOOL_PROTOCOL -->' || CHAR(10) || CHAR(10) || 'Output requirements:')
WHERE writer_agent_system_prompt IS NOT NULL
  AND writer_agent_system_prompt LIKE '%BEGIN_LEGACY_TOOL_PROTOCOL%'
  AND writer_agent_system_prompt NOT LIKE '%END_LEGACY_TOOL_PROTOCOL%';

------------------------------------------------------------------------
-- 4) Strip the wrapped legacy tool-protocol block. After this section
--    the DB column carries only the agent's mode-neutral role description;
--    the transport-specific guidance comes from resource templates loaded
--    by SystemPromptAssembler at runtime.
--
--    Only touches rows where section 3 (or a previous deployment of V12)
--    has already added both markers. Operator-edited prompts without
--    markers are left untouched; SystemPromptAssembler still handles them
--    via its own marker-stripping fallback.
------------------------------------------------------------------------

UPDATE system_prompts
SET issue_agent_system_prompt = REGEXP_REPLACE(
        issue_agent_system_prompt,
        '(?s)\s*<!-- BEGIN_LEGACY_TOOL_PROTOCOL -->.*?<!-- END_LEGACY_TOOL_PROTOCOL -->\s*',
        CHAR(10)),
    updated_at = CURRENT_TIMESTAMP
WHERE issue_agent_system_prompt IS NOT NULL
  AND issue_agent_system_prompt LIKE '%BEGIN_LEGACY_TOOL_PROTOCOL%'
  AND issue_agent_system_prompt LIKE '%END_LEGACY_TOOL_PROTOCOL%';

UPDATE system_prompts
SET writer_agent_system_prompt = REGEXP_REPLACE(
        writer_agent_system_prompt,
        '(?s)\s*<!-- BEGIN_LEGACY_TOOL_PROTOCOL -->.*?<!-- END_LEGACY_TOOL_PROTOCOL -->\s*',
        CHAR(10)),
    updated_at = CURRENT_TIMESTAMP
WHERE writer_agent_system_prompt IS NOT NULL
  AND writer_agent_system_prompt LIKE '%BEGIN_LEGACY_TOOL_PROTOCOL%'
  AND writer_agent_system_prompt LIKE '%END_LEGACY_TOOL_PROTOCOL%';

