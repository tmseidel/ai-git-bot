-- Audit log of AI provider interactions: token usage of successful calls
-- (ai_usage_log) and details of failed calls including stack traces
-- (ai_error_log). Shown on the admin "Usage" page.
-- See the matching h2 migration for the design rationale.

CREATE TABLE IF NOT EXISTS ai_usage_log (
    id BIGSERIAL PRIMARY KEY,
    recorded_at TIMESTAMP NOT NULL,
    ai_integration_name VARCHAR(255) NOT NULL,
    session_id VARCHAR(255),
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_ai_usage_log_timestamp ON ai_usage_log(recorded_at);

CREATE TABLE IF NOT EXISTS ai_error_log (
    id BIGSERIAL PRIMARY KEY,
    recorded_at TIMESTAMP NOT NULL,
    ai_integration_name VARCHAR(255) NOT NULL,
    session_id VARCHAR(255),
    error_message VARCHAR(2000),
    stack_trace TEXT
);

CREATE INDEX IF NOT EXISTS idx_ai_error_log_timestamp ON ai_error_log(recorded_at);
