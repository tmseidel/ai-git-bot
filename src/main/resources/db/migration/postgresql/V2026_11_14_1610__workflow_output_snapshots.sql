-- Supplemental learning evidence for agentic reviews (implementation plan step 1d).
--
-- The task, diff, prompt and revisions a review was produced from. Written only when the
-- agentic-review configuration enables feedback collection, bounded in size, and marked incomplete
-- rather than silently shortened. No credentials are ever stored.
--
-- Idempotent: every statement can be re-applied.

CREATE TABLE IF NOT EXISTS workflow_output_snapshots (
    id                        BIGSERIAL PRIMARY KEY,
    review_id                 BIGINT       NOT NULL,
    output_id                 BIGINT       NOT NULL,
    pr_title                  TEXT,
    pr_body                   TEXT,
    diff_text                 TEXT,
    system_prompt             TEXT,
    system_prompt_hash        VARCHAR(64),
    base_sha                  VARCHAR(64),
    head_sha                  VARCHAR(64),
    base_ref                  VARCHAR(512),
    head_ref                  VARCHAR(512),
    provider_repository_id    VARCHAR(255),
    provider                  VARCHAR(64),
    model                     VARCHAR(128),
    workflow_configuration_id BIGINT,
    settings_hash             VARCHAR(64),
    complete                  BOOLEAN      NOT NULL DEFAULT FALSE,
    incomplete_reason         VARCHAR(1024),
    created_at                TIMESTAMP    NOT NULL,
    CONSTRAINT uk_workflow_output_snapshots_review UNIQUE (review_id),
    CONSTRAINT fk_workflow_output_snapshots_review
        FOREIGN KEY (review_id) REFERENCES review_records(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_workflow_output_snapshots_created
    ON workflow_output_snapshots (created_at);
