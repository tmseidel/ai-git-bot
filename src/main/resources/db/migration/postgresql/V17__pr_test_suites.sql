-- M4 (PR-Review Agentic Workflows): persistence for the E2E test workflow.
-- See V17__pr_test_suites.sql in the H2 tree for the design rationale.

CREATE TABLE IF NOT EXISTS pr_test_suites (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL,
    pr_number BIGINT NOT NULL,
    framework VARCHAR(32) NOT NULL,
    source_tree_ref VARCHAR(255),
    lifecycle_mode VARCHAR(32) NOT NULL DEFAULT 'ephemeral',
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_pr_test_suites_run
        FOREIGN KEY (run_id) REFERENCES pr_workflow_runs(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_pr_test_suites_run_id ON pr_test_suites(run_id);
CREATE INDEX IF NOT EXISTS idx_pr_test_suites_pr_number ON pr_test_suites(pr_number);

CREATE TABLE IF NOT EXISTS pr_test_cases (
    id BIGSERIAL PRIMARY KEY,
    suite_id BIGINT NOT NULL,
    path VARCHAR(1024) NOT NULL,
    title VARCHAR(512),
    content TEXT NOT NULL,
    last_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    last_run_at TIMESTAMP,
    last_duration_ms BIGINT,
    last_log TEXT,
    CONSTRAINT fk_pr_test_cases_suite
        FOREIGN KEY (suite_id) REFERENCES pr_test_suites(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_pr_test_cases_suite_id ON pr_test_cases(suite_id);
