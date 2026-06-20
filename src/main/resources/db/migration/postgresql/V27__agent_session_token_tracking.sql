-- V27: Add cumulative token tracking columns to agent_sessions
-- These columns track total token consumption per agent session for
-- proactive compaction decisions and cost monitoring.

ALTER TABLE agent_sessions ADD COLUMN IF NOT EXISTS total_input_tokens  BIGINT NOT NULL DEFAULT 0;
ALTER TABLE agent_sessions ADD COLUMN IF NOT EXISTS total_output_tokens BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ai_integrations ADD COLUMN IF NOT EXISTS context_window_tokens INT NOT NULL DEFAULT 200000;