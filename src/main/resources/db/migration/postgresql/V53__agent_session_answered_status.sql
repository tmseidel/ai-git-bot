-- Version note: migration 52 is already claimed by the unreleased model-routing branch
-- (`V52__model_routing_configurations.sql`, applied to developer databases since 2026-09-24),
-- and the OpenRouter/model-routing branches both sat on 52 as well. 53 was free across every
-- ref, so this migration takes it instead of joining that collision -- re-check with
-- `git log --all --name-only -- 'src/main/resources/db/migration/' | grep -E 'V5[0-9]__'`
-- before assuming the next number is free.
-- The coding agent can complete a run by answering the issue instead of changing code
-- (read-only / question-only issues). The status column carries a CHECK constraint listing
-- the allowed values, so the constraint has to be re-created with the new value.
-- V1 and V5 applied the same drop/re-add pattern.
ALTER TABLE agent_sessions DROP CONSTRAINT IF EXISTS chk_agent_sessions_status;
ALTER TABLE agent_sessions DROP CONSTRAINT IF EXISTS agent_sessions_status_check;
ALTER TABLE agent_sessions ADD CONSTRAINT chk_agent_sessions_status
    CHECK (status IN ('IN_PROGRESS', 'PR_CREATED', 'UPDATING', 'COMPLETED', 'FAILED', 'ISSUE_CREATED', 'ANSWERED'));
