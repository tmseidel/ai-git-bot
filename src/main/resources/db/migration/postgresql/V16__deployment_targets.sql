-- M3 (PR-Review Agentic Workflows): deployment targets + per-run callback channel.
-- See db/migration/h2/V16__deployment_targets.sql for the conceptual notes;
-- this Postgres flavour mirrors the DDL with information_schema guards for
-- the foreign-key constraint.

CREATE TABLE IF NOT EXISTS deployment_targets (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    strategy_type VARCHAR(32) NOT NULL,
    config_json TEXT NOT NULL,
    preview_url_template VARCHAR(1024),
    timeout_seconds INT NOT NULL DEFAULT 600,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

ALTER TABLE bots ADD COLUMN IF NOT EXISTS deployment_target_id BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_name = 'fk_bots_deployment_target'
          AND table_name = 'bots'
    ) THEN
        ALTER TABLE bots
            ADD CONSTRAINT fk_bots_deployment_target
            FOREIGN KEY (deployment_target_id) REFERENCES deployment_targets(id);
    END IF;
END $$;

ALTER TABLE pr_workflow_runs ADD COLUMN IF NOT EXISTS preview_url VARCHAR(2048);
ALTER TABLE pr_workflow_runs ADD COLUMN IF NOT EXISTS callback_secret VARCHAR(128);
ALTER TABLE pr_workflow_runs ADD COLUMN IF NOT EXISTS deployment_handle_json TEXT;
