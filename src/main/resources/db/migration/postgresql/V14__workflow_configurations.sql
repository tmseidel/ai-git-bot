-- M2 (PR-Review Agentic Workflows): reusable workflow configurations.
-- See db/migration/h2/V14__workflow_configurations.sql for the conceptual notes.
-- Postgres flavour mirrors the H2 DDL with information_schema guards.

CREATE TABLE IF NOT EXISTS workflow_configurations (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    default_entry BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS workflow_selections (
    id BIGSERIAL PRIMARY KEY,
    workflow_configuration_id BIGINT NOT NULL,
    workflow_key VARCHAR(64) NOT NULL,
    params_json TEXT,
    CONSTRAINT uk_workflow_selection UNIQUE (workflow_configuration_id, workflow_key),
    CONSTRAINT fk_workflow_selection_configuration
        FOREIGN KEY (workflow_configuration_id) REFERENCES workflow_configurations(id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_workflow_selection_configuration
    ON workflow_selections (workflow_configuration_id);

CREATE TABLE IF NOT EXISTS workflow_selection_params (
    id BIGSERIAL PRIMARY KEY,
    workflow_selection_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    param_value TEXT,
    CONSTRAINT uk_workflow_selection_param UNIQUE (workflow_selection_id, name),
    CONSTRAINT fk_workflow_selection_param_selection
        FOREIGN KEY (workflow_selection_id) REFERENCES workflow_selections(id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_workflow_selection_param_selection
    ON workflow_selection_params (workflow_selection_id);

ALTER TABLE bots ADD COLUMN IF NOT EXISTS workflow_configuration_id BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_name = 'fk_bots_workflow_configuration'
          AND table_name = 'bots'
    ) THEN
        ALTER TABLE bots
            ADD CONSTRAINT fk_bots_workflow_configuration
            FOREIGN KEY (workflow_configuration_id) REFERENCES workflow_configurations(id);
    END IF;
END $$;

