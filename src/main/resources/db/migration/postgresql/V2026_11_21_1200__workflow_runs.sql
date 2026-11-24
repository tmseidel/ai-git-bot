-- ---------------------------------------------------------------------------------------------
-- Workflow runs (#599)
--
-- workflow_run is one triggered workflow (a review, an Issue Writer run, ...). ai_interaction
-- replaces ai_usage_log and ai_error_log: one row per request to the AI provider, successful or
-- failed, with its round, attempt and timing. ai_tool_call holds the tool calls the model
-- requested in an interaction's response. Only facts are stored; totals (tokens, rounds, tool
-- calls, model and tool time) are computed from the rows when they are read.
--
-- Existing usage and error log rows are migrated into one synthetic "legacy" run per audit
-- session id (owner/repo#n). A session can span several real runs, so a legacy run may cover more
-- than one workflow. Rows without a session id go into a single legacy run. Legacy interactions
-- have no duration, and their start time is when they were logged.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS workflow_run (
    id                          BIGSERIAL PRIMARY KEY,
    workflow_type               VARCHAR(64)   NOT NULL,
    bot_id                      BIGINT,
    bot_name                    VARCHAR(255),
    ai_integration_name         VARCHAR(255),
    git_integration_name        VARCHAR(255),
    repo_owner                  VARCHAR(255),
    repo_name                   VARCHAR(255),
    trigger_kind                VARCHAR(32),
    trigger_number              BIGINT,
    session_id                  VARCHAR(255),
    state                       VARCHAR(16)   NOT NULL,
    error_message               VARCHAR(2000),
    started_at                  TIMESTAMP     NOT NULL,
    ended_at                    TIMESTAMP,
    round_limit_reached         BOOLEAN       NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_workflow_run_started_at ON workflow_run (started_at);
CREATE INDEX IF NOT EXISTS idx_workflow_run_state ON workflow_run (state);
CREATE INDEX IF NOT EXISTS idx_workflow_run_repo ON workflow_run (repo_owner, repo_name);
CREATE INDEX IF NOT EXISTS idx_workflow_run_bot ON workflow_run (bot_id);
CREATE INDEX IF NOT EXISTS idx_workflow_run_type ON workflow_run (workflow_type);

CREATE TABLE IF NOT EXISTS ai_interaction (
    id                          BIGSERIAL PRIMARY KEY,
    workflow_run_id             BIGINT        NOT NULL,
    round_number                INTEGER       NOT NULL,
    attempt                     INTEGER       NOT NULL,
    ai_integration_name         VARCHAR(255)  NOT NULL,
    session_id                  VARCHAR(255),
    status                      VARCHAR(16)   NOT NULL,
    started_at                  TIMESTAMP     NOT NULL,
    duration_ms                 BIGINT,
    stop_reason                 VARCHAR(32),
    input_tokens                BIGINT        NOT NULL DEFAULT 0,
    output_tokens               BIGINT        NOT NULL DEFAULT 0,
    cache_creation_input_tokens BIGINT        NOT NULL DEFAULT 0,
    cache_read_input_tokens     BIGINT        NOT NULL DEFAULT 0,
    error_message               VARCHAR(2000),
    stack_trace                 TEXT,
    raw_request                 TEXT,
    raw_response                TEXT,
    CONSTRAINT fk_ai_interaction_run
        FOREIGN KEY (workflow_run_id) REFERENCES workflow_run (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_ai_interaction_run ON ai_interaction (workflow_run_id, round_number, attempt);
CREATE INDEX IF NOT EXISTS idx_ai_interaction_started_at ON ai_interaction (started_at);
CREATE INDEX IF NOT EXISTS idx_ai_interaction_status ON ai_interaction (status);

CREATE TABLE IF NOT EXISTS ai_tool_call (
    id                          BIGSERIAL PRIMARY KEY,
    interaction_id              BIGINT        NOT NULL,
    sequence                    INTEGER       NOT NULL,
    source                      VARCHAR(255)  NOT NULL,
    tool_name                   VARCHAR(255)  NOT NULL,
    duration_ms                 BIGINT        NOT NULL,
    success                     BOOLEAN       NOT NULL,
    error_message               VARCHAR(2000),
    arguments                   TEXT,
    CONSTRAINT fk_ai_tool_call_interaction
        FOREIGN KEY (interaction_id) REFERENCES ai_interaction (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_ai_tool_call_interaction ON ai_tool_call (interaction_id, sequence);

-- ---------------------------------------------------------------------------------------------
-- Legacy data
-- ---------------------------------------------------------------------------------------------

INSERT INTO workflow_run (workflow_type, ai_integration_name, repo_owner, repo_name, trigger_number,
                          session_id, state, error_message, started_at, ended_at)
SELECT 'legacy',
       MIN(l.ai_integration_name),
       CASE WHEN l.session_id ~ '^.+/[^/#]+(#[0-9]{1,18})?$' THEN regexp_replace(l.session_id, '^(.+)/([^/#]+)(#[0-9]{1,18})?$', '\1') END,
       CASE WHEN l.session_id ~ '^.+/[^/#]+(#[0-9]{1,18})?$' THEN regexp_replace(l.session_id, '^(.+)/([^/#]+)(#[0-9]{1,18})?$', '\2') END,
       CASE WHEN l.session_id ~ '#[0-9]{1,18}$' THEN CAST(substring(l.session_id from '#([0-9]{1,18})$') AS BIGINT) END,
       l.session_id,
       CASE WHEN SUM(l.is_error) > 0 THEN 'ERROR' ELSE 'SUCCEEDED' END,
       CASE WHEN SUM(l.is_error) > 0 THEN 'Contains failed AI interactions' END,
       MIN(l.recorded_at),
       MAX(l.recorded_at)
FROM (SELECT session_id, ai_integration_name, recorded_at, 0 AS is_error FROM ai_usage_log
      UNION ALL
      SELECT session_id, ai_integration_name, recorded_at, 1 FROM ai_error_log) l
GROUP BY l.session_id;

INSERT INTO ai_interaction (workflow_run_id, round_number, attempt, ai_integration_name, session_id,
                            status, started_at, input_tokens, output_tokens,
                            cache_creation_input_tokens, cache_read_input_tokens, error_message,
                            stack_trace, raw_request, raw_response)
SELECT r.id,
       ROW_NUMBER() OVER (PARTITION BY r.id ORDER BY l.recorded_at, l.kind, l.id),
       1,
       l.ai_integration_name,
       l.session_id,
       l.status,
       l.recorded_at,
       l.input_tokens,
       l.output_tokens,
       l.cache_creation_input_tokens,
       l.cache_read_input_tokens,
       l.error_message,
       l.stack_trace,
       l.raw_request,
       l.raw_response
FROM (SELECT id, 0 AS kind, session_id, ai_integration_name, 'SUCCEEDED' AS status, recorded_at,
             input_tokens, output_tokens, cache_creation_input_tokens, cache_read_input_tokens,
             CAST(NULL AS VARCHAR(2000)) AS error_message, CAST(NULL AS TEXT) AS stack_trace,
             raw_request, raw_response
      FROM ai_usage_log
      UNION ALL
      SELECT id, 1, session_id, ai_integration_name, 'ERROR', recorded_at, 0, 0, 0, 0,
             error_message, stack_trace, CAST(NULL AS TEXT), CAST(NULL AS TEXT)
      FROM ai_error_log) l
JOIN workflow_run r
  ON r.workflow_type = 'legacy'
 AND (r.session_id = l.session_id OR (r.session_id IS NULL AND l.session_id IS NULL));

DROP TABLE IF EXISTS ai_usage_log;
DROP TABLE IF EXISTS ai_error_log;
